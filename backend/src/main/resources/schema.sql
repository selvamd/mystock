-- mystock DuckDB schema (idempotent). Legacy SQLite/CSV -> table mapping in docs/ARCHITECTURE.md.

-- usstocks.db: stocks. Overview columns keep the legacy Alpha Vantage names and TEXT type so the legacy
-- StockDB logic (sector mapping, MktSize buckets, numeric parsing) runs unchanged.
CREATE TABLE IF NOT EXISTS symbols (
  symid INTEGER PRIMARY KEY,
  ticker VARCHAR NOT NULL,
  AssetType VARCHAR, Name VARCHAR, Description VARCHAR, Exchange VARCHAR, Currency VARCHAR, Country VARCHAR,
  Sector VARCHAR, Industry VARCHAR, Address VARCHAR, FullTimeEmployees VARCHAR, FiscalYearEnd VARCHAR,
  LatestQuarter VARCHAR, MarketCapitalization VARCHAR, EBITDA VARCHAR, PERatio VARCHAR, PEGRatio VARCHAR,
  BookValue VARCHAR, DividendPerShare VARCHAR, DividendYield VARCHAR, EPS VARCHAR, RevenuePerShareTTM VARCHAR,
  ProfitMargin VARCHAR, OperatingMarginTTM VARCHAR, ReturnOnAssetsTTM VARCHAR, ReturnOnEquityTTM VARCHAR,
  RevenueTTM VARCHAR, GrossProfitTTM VARCHAR, DilutedEPSTTM VARCHAR, QuarterlyEarningsGrowthYOY VARCHAR,
  QuarterlyRevenueGrowthYOY VARCHAR, AnalystTargetPrice VARCHAR, TrailingPE VARCHAR, ForwardPE VARCHAR,
  PriceToSalesRatioTTM VARCHAR, PriceToBookRatio VARCHAR, EVToRevenue VARCHAR, EVToEBITDA VARCHAR, Beta VARCHAR,
  d52WeekHigh VARCHAR, d52WeekLow VARCHAR, d50DayMovingAverage VARCHAR, d200DayMovingAverage VARCHAR,
  SharesOutstanding VARCHAR, SharesFloat VARCHAR, SharesShort VARCHAR, SharesShortPriorMonth VARCHAR,
  ShortRatio VARCHAR, ShortPercentOutstanding VARCHAR, ShortPercentFloat VARCHAR, PercentInsiders VARCHAR,
  PercentInstitutions VARCHAR, ForwardAnnualDividendRate VARCHAR, ForwardAnnualDividendYield VARCHAR,
  PayoutRatio VARCHAR, DividendDate VARCHAR, ExDividendDate VARCHAR, LastSplitFactor VARCHAR, LastSplitDate VARCHAR,
  etlstatus INTEGER
);

-- usstocks.db: stocks_status (legacy shape: yyyymmdd int + adjclose in int cents)
CREATE TABLE IF NOT EXISTS price_status (
  symid INTEGER PRIMARY KEY,
  lastdt INTEGER,
  lastpx INTEGER
);

-- eodmktpx.db: quotes. Dollars (legacy truncated cents / 100, so cents round-trip exactly) and DATE.
CREATE TABLE IF NOT EXISTS prices (
  ticker VARCHAR NOT NULL,
  "asof" DATE NOT NULL,
  open DOUBLE, high DOUBLE, low DOUBLE, close DOUBLE, adjclose DOUBLE,
  volume BIGINT,
  PRIMARY KEY (ticker, "asof")
);

-- stockvol.db: stockvol (legacy shape; next_earnings_date is yyyymmdd as double, lastupdate epoch ms)
CREATE TABLE IF NOT EXISTS stock_vol (
  ticker VARCHAR PRIMARY KEY,
  next_earnings_date DOUBLE,
  pe_ratio DOUBLE,
  mktcap DOUBLE,
  impliedvol DOUBLE,
  lastupdate BIGINT
);

-- oxeodmktpx.db: quotes (dollars, decimals, DATE)
CREATE TABLE IF NOT EXISTS option_quotes (
  contract_id VARCHAR NOT NULL,
  symbol VARCHAR,
  type VARCHAR,
  "asof" DATE NOT NULL,
  expiration DATE,
  strike DOUBLE, last DOUBLE, mark DOUBLE, bid DOUBLE, bid_size INTEGER, ask DOUBLE, ask_size INTEGER,
  volume BIGINT, open_interest BIGINT,
  implied_volatility DOUBLE, delta DOUBLE, gamma DOUBLE, theta DOUBLE, vega DOUBLE, rho DOUBLE,
  upx DOUBLE,
  PRIMARY KEY (contract_id, "asof")
);

-- oxeodmktpx.db: stockfeatures (StockFeature.headers())
CREATE TABLE IF NOT EXISTS stock_features (
  symbol VARCHAR NOT NULL, "asof" DATE NOT NULL,
  mktcap DOUBLE, pe DOUBLE, eps DOUBLE, dividend DOUBLE, shares DOUBLE, daypnl DOUBLE, weekpnl DOUBLE, mompnl DOUBLE,
  qoqpnl DOUBLE, triskvol DOUBLE, yoypnl DOUBLE, roi DOUBLE, corr DOUBLE, upcorr DOUBLE, downcorr DOUBLE, coskew DOUBLE,
  beta DOUBLE, alpha DOUBLE, pnldir DOUBLE, aroonup DOUBLE, aroondwn DOUBLE, hangtime DOUBLE, qpeak DOUBLE,
  rangescore DOUBLE, qdrop DOUBLE, range DOUBLE, lastdevz DOUBLE, optvol DOUBLE, optivol DOUBLE, kurt DOUBLE,
  trisktouch DOUBLE, triskbreach DOUBLE, rsi DOUBLE, weekdip DOUBLE, momdip DOUBLE, bumpiness DOUBLE, r2 DOUBLE,
  histvol DOUBLE, stlpm DOUBLE, dtpm DOUBLE, uptic DOUBLE, ema20 DOUBLE, ema60 DOUBLE, ema200 DOUBLE, peak30 DOUBLE,
  drop30 DOUBLE, seasonality DOUBLE, seasonindex DOUBLE, px2bbpct DOUBLE, momtr DOUBLE, cci DOUBLE, cciq DOUBLE,
  qrebound DOUBLE, prscore DOUBLE, maxpctchg DOUBLE, sharpness DOUBLE, qdropavg DOUBLE, signal DOUBLE, xadv DOUBLE,
  volratio DOUBLE, rangevol DOUBLE,
  PRIMARY KEY (symbol, "asof")
);

-- financials.db: pnlstmt -> long format (Alpha Vantage INCOME_STATEMENT / BALANCE_SHEET)
CREATE TABLE IF NOT EXISTS financials (
  ticker VARCHAR NOT NULL,
  statement VARCHAR NOT NULL,   -- income | balance
  fiscal_date DATE NOT NULL,
  period VARCHAR NOT NULL,      -- annual | quarterly
  field VARCHAR NOT NULL,
  value DOUBLE,
  fetched_at TIMESTAMP,
  PRIMARY KEY (ticker, statement, fiscal_date, period, field)
);

-- trades.db: login -> users (PBKDF2) + sessions
CREATE TABLE IF NOT EXISTS users (
  login VARCHAR PRIMARY KEY,
  pass_hash VARCHAR NOT NULL,
  created_at TIMESTAMP DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS sessions (
  token VARCHAR PRIMARY KEY,
  login VARCHAR NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

-- trades.db: trades_log
CREATE TABLE IF NOT EXISTS trades (
  trade_id BIGINT PRIMARY KEY,
  login VARCHAR, portfolio VARCHAR, position VARCHAR, buysell VARCHAR, legsym VARCHAR,
  px DOUBLE, qty INTEGER, tradedt DATE, upx DOUBLE, uvol DOUBLE,
  created_at TIMESTAMP DEFAULT current_timestamp
);

-- out.csv
CREATE TABLE IF NOT EXISTS stock_metrics (
  "asof" DATE NOT NULL, ticker VARCHAR NOT NULL, name VARCHAR, sect VARCHAR, ind VARCHAR, mktsize VARCHAR,
  symid INTEGER, mktcap DOUBLE, pe DOUBLE, eps DOUBLE, dividend DOUBLE, shares DOUBLE, ttmearnings DOUBLE,
  ttmbookvalue DOUBLE, ttmrevenue DOUBLE, ttmgrossprofit DOUBLE, daypnl DOUBLE, weekpnl DOUBLE, mompnl DOUBLE,
  qoqpnl DOUBLE, yoypnl DOUBLE, pnldir DOUBLE, cagr DOUBLE, corr DOUBLE, upcorr DOUBLE, downcorr DOUBLE,
  coskew DOUBLE, range DOUBLE, rangescore DOUBLE, lastdevz DOUBLE, seasonality DOUBLE, seasonindex DOUBLE,
  xadv DOUBLE, avgdr DOUBLE, momtr DOUBLE, px2bbpct DOUBLE, volratio DOUBLE, signal DOUBLE, aroonup DOUBLE,
  aroondwn DOUBLE, hangtime DOUBLE, peak30 DOUBLE, drop30 DOUBLE, qpeak DOUBLE, prscore DOUBLE, qdrop DOUBLE,
  qrebound DOUBLE, precovpx DOUBLE, decovpx DOUBLE, kurt DOUBLE, trisktouch DOUBLE, triskbreach DOUBLE,
  triskvol DOUBLE, qdropavg DOUBLE, volratioyy DOUBLE, maxpctchg DOUBLE, sharpness DOUBLE, volscore DOUBLE,
  histvol DOUBLE, optvol DOUBLE, optivol DOUBLE, rangevol DOUBLE, rsi DOUBLE, weekdip DOUBLE, momdip DOUBLE,
  bumpiness DOUBLE, r2 DOUBLE, maxdrs DOUBLE, mindrs DOUBLE, stlpm DOUBLE, dtpm DOUBLE, ema20 DOUBLE, ema60 DOUBLE,
  ema200 DOUBLE, beta DOUBLE, alpha DOUBLE, lastpx DOUBLE, mktrscore DOUBLE, mktdip DOUBLE, mktsindex DOUBLE,
  summ VARCHAR, impvol DOUBLE, earnings INTEGER, income VARCHAR, revenue VARCHAR,
  PRIMARY KEY ("asof", ticker)
);

-- port.csv
CREATE TABLE IF NOT EXISTS portfolio_metrics (
  "asof" DATE NOT NULL, name VARCHAR NOT NULL, daypnl DOUBLE, weekpnl DOUBLE, mompnl DOUBLE, qoqpnl DOUBLE,
  yoypnl DOUBLE, roi DOUBLE, corr DOUBLE, upcorr DOUBLE, downcorr DOUBLE, coskew DOUBLE, seasonality DOUBLE,
  seasonindex DOUBLE, aroonup DOUBLE, aroondwn DOUBLE, hangtime DOUBLE, peak30 DOUBLE, drop30 DOUBLE,
  px2bbpct DOUBLE, qpeak DOUBLE, momtr DOUBLE, rangescore DOUBLE, prscore DOUBLE, qdrop DOUBLE, qrebound DOUBLE,
  range DOUBLE, lastdevz DOUBLE, optvol DOUBLE, optivol DOUBLE, kurt DOUBLE, trisktouch DOUBLE,
  triskbreach DOUBLE, rsi DOUBLE, weekdip DOUBLE, momdip DOUBLE, bumpiness DOUBLE, r2 DOUBLE, histvol DOUBLE,
  stlpm DOUBLE, dtpm DOUBLE, ema20 DOUBLE, ema60 DOUBLE, ema200 DOUBLE, beta DOUBLE, alpha DOUBLE,
  mktrscore DOUBLE, mktdip DOUBLE, mktsindex DOUBLE, wgtype VARCHAR,
  PRIMARY KEY ("asof", name)
);

-- StockFeat.builder raw series (EW / MW) as of the last STOCKFEAT run
CREATE TABLE IF NOT EXISTS portfolio_series (
  name VARCHAR NOT NULL, kind VARCHAR NOT NULL, "asof" DATE NOT NULL, value DOUBLE,
  PRIMARY KEY (name, kind, "asof")
);

-- train.csv / pred.csv (columns: StockFeature.header("stk_"/"sect_"/"mkt_"))
CREATE TABLE IF NOT EXISTS model_training (ticker VARCHAR, trddt DATE, target DOUBLE);
CREATE TABLE IF NOT EXISTS model_features ("asof" DATE, ticker VARCHAR, name VARCHAR);

-- prediction.csv
CREATE TABLE IF NOT EXISTS predictions (
  ticker VARCHAR, "asof" DATE, tgtpx DOUBLE, open DOUBLE, high DOUBLE, low DOUBLE, close DOUBLE, reason VARCHAR
);

-- queries.csv / dynamic_reports.csv
CREATE TABLE IF NOT EXISTS saved_queries (name VARCHAR PRIMARY KEY, value VARCHAR);
CREATE TABLE IF NOT EXISTS dynamic_reports ("function" VARCHAR, report VARCHAR, params VARCHAR);

CREATE SEQUENCE IF NOT EXISTS job_runs_seq;
CREATE TABLE IF NOT EXISTS job_runs (
  id BIGINT PRIMARY KEY DEFAULT nextval('job_runs_seq'),
  job VARCHAR, status VARCHAR, started_at TIMESTAMP, finished_at TIMESTAMP, message VARCHAR
);
