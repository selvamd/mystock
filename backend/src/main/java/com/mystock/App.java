package com.mystock;

import com.mystock.api.Ops;
import com.mystock.api.Server;
import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.jobs.JobRunner;
import com.mystock.jobs.Scheduler;
import com.mystock.jobs.VolLoop;
import com.mystock.legacy.Legacy;
import com.mystock.services.AuthService;
import com.mystock.tools.Migrate;
import com.mystock.tools.SeedDemo;

import java.nio.file.Path;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;

/** CLI: server | task JOB | migrate DIR | adduser LOGIN PASSWORD | seed-demo */
public final class App {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); }
        Config cfg = Config.fromEnv();
        init(cfg);
        String cmd = args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "server" -> server(cfg);
            case "task" -> {
                if (args.length < 2) { usage(); System.exit(2); }
                String job = args[1].toUpperCase(Locale.ROOT);
                if (!JobRunner.isJob(job)) { System.err.println("unknown job " + job + "; jobs: " + JobRunner.API_JOBS + " " + JobRunner.CLI_JOBS); System.exit(2); }
                try (Database db = Database.open(cfg.dbFile()); JobRunner jr = new JobRunner()) {
                    Legacy.reload();
                    boolean ok = jr.runNow(job);
                    System.exit(ok ? 0 : 1);
                }
            }
            case "migrate" -> {
                if (args.length < 2) { usage(); System.exit(2); }
                try (Database db = Database.open(cfg.dbFile())) {
                    new Migrate().run(Path.of(args[1]));
                }
                System.exit(0);
            }
            case "adduser" -> {
                if (args.length < 3) { usage(); System.exit(2); }
                try (Database db = Database.open(cfg.dbFile())) {
                    new AuthService().addUser(args[1], args[2]);
                    System.out.println("user " + args[1] + " saved");
                }
                System.exit(0);
            }
            case "seed-demo" -> {
                try (Database db = Database.open(cfg.dbFile())) {
                    new SeedDemo().run();
                }
                System.exit(0);
            }
            default -> { usage(); System.exit(2); }
        }
    }

    /** Process-wide settings the legacy code depends on (default locale/zone for DecimalFormat/Calendar). */
    public static void init(Config cfg) throws Exception {
        Config.set(cfg);
        // Legacy DecimalFormat("#.##") output is parsed back as CSV: force '.' decimals.
        Locale.setDefault(Locale.US);
        // Legacy Dates.today()/Calendar use the JVM default zone.
        TimeZone.setDefault(TimeZone.getTimeZone(cfg.tz));
        Legacy.setWorkDir(cfg.workDir());
    }

    static void server(Config cfg) throws Exception {
        Database.open(cfg.dbFile());
        JobRunner jobs = new JobRunner();
        boolean ok = Legacy.reload();
        System.out.println(ok ? "market context loaded, db_date=" + Legacy.dbdate() : "market context empty (no RSP prices yet)");
        Server server = new Server(cfg.apiPort, cfg.apiKey, new Ops(jobs));
        server.start();
        Scheduler sched = null;
        if (cfg.schedulerEnabled) { sched = new Scheduler(jobs, cfg.eodTime, cfg.tz); sched.start(); }
        VolLoop vol = null;
        if (cfg.volLoopEnabled) { vol = new VolLoop(cfg.volLoopSleepSeconds); vol.start(); }
        CountDownLatch done = new CountDownLatch(1);
        Scheduler fs = sched;
        VolLoop fv = vol;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (fs != null) fs.close();
            if (fv != null) fv.close();
            jobs.close();
            Database.current().close();
            done.countDown();
        }));
        done.await();
    }

    static void usage() {
        System.err.println("""
                usage: java -jar mystock-backend.jar <command>
                  server                    socket API + EOD scheduler + vol loop (one JVM owns the DuckDB file)
                  task <JOB>                run one job and exit (server must be stopped)
                                            jobs: SYMBOLS SYMBOLS_FULL PRICES STOCKINFO STOCKFEAT MODEL MODEL_TRAIN
                                                  VOL_ONCE OPTIONS OPTFEAT FINANCIALS EOD
                  migrate <legacyDataDir>   import legacy SQLite/CSV files
                  adduser <login> <password>
                  seed-demo                 deterministic offline demo dataset
                """);
    }
}
