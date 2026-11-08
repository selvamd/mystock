package com.mystock.legacy;

import java.util.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class Dates
{
	private static final int[] DAYS_MONTH = new int[] { 31,28,31,30,31,30,31,31,30,31,30,31 };

	//Provides contigious series of dates for all time series calculations.
	//Expectoed to be used as a system wide function
	public static int[] dates;
	public static void init(int fromdt, int todt)
	{
		List<Integer> values = new ArrayList<Integer>();
		for (int dt = fromdt; dt <= todt; dt = offset(dt,1))
			values.add(dt);
		dates = values.stream().mapToInt(i->i).toArray();
	}

	public static int lastdt() {
		return dates[dates.length-1];
	}

	public static int today() {
		Calendar c = Calendar.getInstance();
		return c.get(Calendar.YEAR)*10000 + (1+c.get(Calendar.MONTH)) * 100 + c.get(Calendar.DAY_OF_MONTH);
	}

	public static int todate(int idx) { return dates[idx]; }

	public static int toindex(int dt)
	{
		int idx =  Arrays.binarySearch(dates,dt);
		if (idx >= 0) return idx;
		return (idx == -1)? 0:dates.length;
	}

	public static int offset(int dt, int days) {
		LocalDate dte = LocalDate.of(dt/10000,(dt/100)%100,dt%100);
		dte = dte.plusDays(days);
		return dte.getYear()*10000+dte.getMonthValue()*100+dte.getDayOfMonth();
	}

	// public static int nextDay(int input)
	// {
	//     int inyy = input/10000, inmm = (input%10000)/100, indd = 1+(input%100);
	//     boolean leapstatus = (inyy % 4 == 0 && (inyy % 100 > 0 || inyy % 400 == 0));
	//     int monthmax = (leapstatus && inmm == 2 )? DAYS_MONTH[inmm-1]+1:DAYS_MONTH[inmm-1];
	//
	//     if (indd > monthmax)
	//     {
	//         indd = 1;
	//         inmm++;
	//     }
	//
	//     if (inmm == 13)
	//     {
	//         inyy++;
	//         inmm = 1;
	//     }
	//     return inyy*10000+ inmm * 100 + indd;
	// }
	public static int dateToDays(int dt) {
		LocalDate dte1 = LocalDate.of(dt/10000,(dt/100)%100,dt%100);
		int today = today();
		LocalDate dte0 = LocalDate.of(today/10000,(today/100)%100,today%100);
		return (int)ChronoUnit.DAYS.between(dte0,dte1);
	}

	// public static int dateToDays(int input)
	// {
	//     int inyy = input/10000, inmm = (input%10000)/100, indd = input%100;
	//     boolean leapstatus = (inyy % 4 == 0 && (inyy % 100 > 0 || inyy % 400 == 0));
	//     inyy--;
	//     int leapyears = (inyy/4) - (inyy/100) + (inyy/400);
	//     int days = (leapyears * 366) + (inyy-leapyears)*365;
	//     for (int i=1;i < inmm; i++)
	//         days += ((leapstatus && i == 2 )? DAYS_MONTH[i-1]+1:DAYS_MONTH[i-1]);
	//     days += indd;
	//     return days;
	// }

	public static void main(String[] args) throws Exception
	{
		System.out.println(dateToDays(Integer.parseInt(args[0])));
	}

}
