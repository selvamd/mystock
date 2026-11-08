package com.mystock;

import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.golden.GoldenExport;
import com.mystock.tools.SeedDemo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** One seeded demo database (fixed end date) shared by all tests in the JVM. */
public final class Fixture {
    private static Path dir;

    public static synchronized Path seeded() throws Exception {
        if (dir != null) return dir;
        Path d = Files.createTempDirectory("mystock-test");
        App.init(new Config(Map.of("MYSTOCK_DATA_DIR", d.toString(), "MYSTOCK_TZ", "America/New_York",
                "MYSTOCK_YAHOO_ENABLED", "false", "MYSTOCK_API_KEY", "test-key")));
        Database.open(d.resolve("mystock.duckdb"));
        new SeedDemo().run(GoldenExport.END, true);
        dir = d;
        return d;
    }
}
