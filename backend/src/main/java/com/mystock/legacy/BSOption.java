package com.mystock.legacy;

import java.lang.Math;

public class BSOption {

    public double S;        // underlying asset price
    public double K;        // strike price option
    public double T;        // time to maturity option (yr fraction)
    public double r;        // annual risk-free rate
    public double q;        // annual yield rate asset
    public double vol;      // underlying asset volatility
    public char type;

    // greeks
    public static final int DELTA = 0;
    public static final int VEGA = 1;
    public static final int PSI = 2;
    public static final int THETA = 3;
    public static final int RHO = 4;
    public static final int GAMMA = 5;
    public static final int VOLGA = 6;


    // default constructor
    BSOption(){}

    // defined constructor
    BSOption(double s, double k, double t, double vol, char type) {
      this.S = s;
      this.K = k;
      this.T = t;
      this.r = 0.05;
      this.q = 0.05;
      this.vol = vol;
      this.type = type;
    }

    private BSOption objclone() {
        return new BSOption(S,K,T,vol,type);
    }

    public double strike(double delta) {
        return _strike_(delta,0,Math.max(S-100,0),S+1000.0);
    }

    private double _strike_(double delta, int cnt, double l, double h) {
         BSOption opt = objclone();
         opt.type = (delta < 0)? 'p':'c';
         opt.K = (l+h)/2.0;
         if (cnt == 100) return opt.K;
         double ld = opt.computeGreeks()[DELTA];
         if (Math.abs(ld - delta) > 0.01)
             return (ld < delta)? _strike_(delta,++cnt,l,opt.K):_strike_(delta,++cnt,opt.K,h);
         return opt.K;
    }

    private double optprice(double spot, double strike, double iv, double expyr)
    {
        double dplus = (Math.log(spot / strike) + (r - q + Math.pow(iv,2) / 2) * (expyr)) / (iv * Math.sqrt((expyr)));
        double dminus = dplus - iv * Math.sqrt((expyr));
        double price;
        if (type == 'c') price = spot * CND(dplus) * Math.exp(-q * (expyr)) - strike * CND(dminus) * Math.exp(-r * (expyr)); // price of a call option
        else price = spot * -1 * CND(-dplus) * Math.exp(-q * (expyr)) + strike * CND(-dminus) * Math.exp(-r * (expyr));             // price of a put option
        return price;
    }

    public double computePrice() { return optprice(S,K,vol,T); }
    public double computePriceVol(double v) { return optprice(S,K,v,T); }
    public double computePrice(double spot, double iv, double expryr) {
        return optprice(spot,K,iv,expryr);
    }
    public double computePriceVolStrike(double v, double strike, double expryr) {
        return optprice(S,strike,v,expryr);
    }
    public double computePriceDiagram (double spot, double t) {
        return optprice(spot,K,vol,T-t);
    }

   public double[] computeGreeks() {
      double dplus = (Math.log(S / K) + (r - q + Math.pow(vol,2) / 2) * T) / (vol * Math.sqrt(T));
      double dminus = dplus - vol * Math.sqrt(T);
      double[] greeks = new double[7];
      if (type == 'c') {
         greeks[DELTA] = Math.exp(-q*T)*CND(dplus);
         greeks[VEGA]  = S*Math.exp(-q*T)*ND(dplus)*Math.sqrt(T);
         greeks[PSI]   = -S*Math.exp(-q*T)*(Math.sqrt(T)/vol*ND(dplus)+T*CND(dplus))+K*Math.exp(-r*T)*ND(dminus)*Math.sqrt(T)/vol;
         greeks[THETA] = -S*ND(dplus)*vol/(2*Math.sqrt(T))-r*K*Math.exp(-r*T)*CND(dminus)+q*S*Math.exp(-q*T)*CND(dplus);
         greeks[RHO]   = K*T*Math.exp(-r*T)*CND(dminus);
         greeks[GAMMA] = Math.exp(-q*T)*ND(dplus)/(S*vol*Math.sqrt(T)) ;
         greeks[VOLGA] = greeks[1]*dplus*dminus/vol;
      }
      else {
         greeks[DELTA] = -Math.exp(-q*T)*CND(-dplus);
         greeks[VEGA]  = S*Math.exp(-q*T)*ND(dplus)*Math.sqrt(T);
         greeks[PSI]   = S*Math.exp(-q*T)*(-Math.sqrt(T)/vol*ND(-dplus)+T*CND(-dplus))+K*Math.exp(-r*T)*ND(-dminus)*Math.sqrt(T)/vol;
         greeks[THETA] = -S*ND(dplus)*vol/(2*Math.sqrt(T))+r*K*Math.exp(-r*T)*CND(-dminus)-q*S*Math.exp(-q*T)*CND(-dplus);
         greeks[RHO]   = -K*T*Math.exp(-r*T)*CND(-dminus);
         greeks[GAMMA] = Math.exp(-q*T)*ND(dplus)/(S*vol*Math.sqrt(T)) ;
         greeks[VOLGA] = greeks[1]*dplus*dminus/vol;
      }
      return greeks;
   }

   public double computeIV(double spot) {
       return computeIV(spot, K, T);
   }

   public double computeIV(double spot, double strike, double expryr) {
        return ImpliedVol_NewtonRaphson(spot, strike, expryr, vol, 100, 0.001);
   }

   // used for implied vol for volatility smile
   private double ImpliedVol_NewtonRaphson(double spotPrice, double strike, double expryr, double guess, int maxIter, double epsilon) {
      int i = 0;
      double X2 = guess;
      double p = computePriceVolStrike(X2, strike, expryr) - spotPrice;
      while (i < maxIter && Math.abs(p) > epsilon) {
         double dplus = (Math.log(S / strike) + (r - q + Math.pow(X2,2) / 2) * (expryr)) / (X2* Math.sqrt((expryr)));
         X2 = X2 - (computePriceVolStrike(X2, strike, expryr) - spotPrice) / (S*Math.exp(-q*(expryr))*ND(dplus)*Math.sqrt(expryr)); // divide by vega (the derivative of price regarding volatility)
         p = computePriceVolStrike(X2, strike, expryr) - spotPrice;
         i++;
      }
      return X2;
   }

   private double ImpliedVol_Secant(double spotPrice, double strike, double expryr, double guess1, double guess2, int maxIter, double epsilon) {
      int i=0;
      double X1 = guess1;
      double X2 = guess2;
      double p = computePriceVolStrike(X2, strike, expryr) - spotPrice;
      while (i < maxIter && Math.abs(p) > epsilon) {
         double temp = X2;
         X2 = X2 - (computePriceVolStrike(X2, strike, expryr) - spotPrice) * (X2 - X1) / (computePriceVolStrike(X2, strike, expryr)-computePriceVolStrike(X1, strike, expryr));
         p = computePriceVolStrike(X2, strike, expryr) - spotPrice;
         X1 = temp;
         i++;
      }
      return X2;
   }

   private double ImpliedVol_RegulaFalsi(double spotPrice, double guess1, double guess2, int maxIter, double epsilon) {
      int i=0;
      double X1 = guess1;
      double X2 = guess2;
      double p = computePriceVol(X2) - spotPrice;
      while (i < maxIter && Math.abs(p) > epsilon) {
         double bk = X2;
         X2 = X2 - (computePriceVol(X2) - spotPrice) * (X2 - X1) / (computePriceVol(X2)-computePriceVol(X1));
         p = computePriceVol(X2) - spotPrice;
         if (p * (computePriceVol(bk) - spotPrice) >0 ) {
            X1 = X2;
            X2 = bk;
         }
         i++;
      }
      return X2;
   }

   // other helpers functions
   private static double ND(double x) {
      return 1/Math.sqrt(2 * Math.PI) * Math.exp(-Math.pow(x,2)/2);
   }

   private static double CND(double x) {

      // coefficients obtained
      double a1 = 0.31938153;
      double a2 = -0.356563782;
      double a3 = 1.781477937;
      double a4 = -1.821255978;
      double a5 = 1.330274429;

      double L = Math.abs(x);
      double K = 1 / (1 + 0.2316419 * L);
      double res = 1 - 1 / Math.sqrt(2 * Math.PI) * Math.exp(-Math.pow(L,2) / 2) * (a1 * K + a2 * Math.pow(K,2) + a3 * Math.pow(K,3) + a4 * Math.pow(K,4) + a5 * Math.pow(K,5));
      if (x<0)
         // if x negative, then reverse approximation by applying 1-x
         res = 1 - res;
      return res;
   }

}
