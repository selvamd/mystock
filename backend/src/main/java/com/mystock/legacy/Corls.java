package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;

//calculates non-timeseries correlation and regression line (least sqaure)
public class Corls
{
	private double Ex = 0;
	private double Ey = 0;
	private double N = 0;
	private double Exy = 0;
	private double Ex2 = 0;
	private double Ey2 = 0;

	public void process(double x, double y) 
	{
		Ex += x;Ey += y;
		Exy += x * y;Ex2 += x * x;
		Ey2 += y * y;N += 1.0;
	}

	//m = (N Σ(xy) − Σx.Σy)/(N Σx2 − (Σx.Σx))
	public double slope() { 
		double r = N * Exy - Ex * Ey;
		double x = N * Ex2 - Ex * Ex;
		return r/x; 
	}
	//b = (Σy − m Σx)/N  
	public double cons()  { return (Ey - slope() * Ex)/N; }
	
	public double yval(double xval) { return cons() + slope() * xval; }
	
	//Corr = (NΣxy-Σx*Σy)/sqrt((NΣx2-ΣxΣx)*(NΣy2-ΣyΣy))
	public double corr() {
		double r = N * Exy - Ex * Ey;
		double x = N * Ex2 - Ex * Ex;
		double y = N * Ey2 - Ey * Ey;
		return r/Math.sqrt(x*y);
	}

    public static void main(String[] args) throws Exception
    {
		Corls c = new Corls();
		for (int i=1;i<=100;i++)
			c.process(i,i+10);
		System.out.println(c.slope());
		System.out.println(c.cons());
		System.out.println(c.corr());
		System.out.println(c.yval(12));
	}
}
