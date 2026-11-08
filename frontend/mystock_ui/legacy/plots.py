import sys
import pandas as pd
#import matplotlib.pyplot as plt
import plotly.express as px
import plotly.graph_objects as go
import math
import plotly.offline as pyo
import numpy as np

def saveplots(figlist, filename="c:\\Temp\\plotly.html"):   # CHANGED: escaped backslashes (invalid escape warning)
    dashboard = open(filename, 'w')
    dashboard.write("<html><head></head><body>" + "\n")
    add_js = True
    for fig in figlist:

        inner_html = pyo.plot(
            fig, include_plotlyjs=add_js, output_type='div'
        )
        dashboard.write(inner_html)
        add_js = False
    dashboard.write("</body></html>" + "\n")

def mvavg(df, xdt, yval, win):
    df1 = df[[xdt,yval]].copy()
    df1 = df1.sort_values(xdt)
    df1['value'] = df1.iloc[:,1].rolling(window=win).mean()
    df1 = df1.drop([yval], axis=1)
    return df1

def residue(df, xdt, yval):
    df1 = df[[xdt,yval]].copy()
    bd = pd.Timestamp('2000-01-01')
    X = df1[xdt].values
    Y = df1[yval].values
    numer, denom, n, ex, ey = 0, 0, 0, 0, 0
    for i in range(len(X)):
        if math.isnan(Y[i]) == False:
            n += 1
            x = pd.Timestamp(X[i]) - bd
            ex += x.days
            ey += Y[i]
    ex = ex/n
    ey = ey/n
    for i in range(len(X)):
        if math.isnan(Y[i]) == False:
            x = pd.Timestamp(X[i]) - bd
            numer += (x.days - ex) * (Y[i] - ey)
            denom += (x.days - ex) ** 2
    m = numer / denom
    c = ey - (m * ex)
    df1["exp"] = df1.apply(lambda row: (pd.Timestamp(row[xdt]) - bd).days * m + c, axis=1)
    df1["value"] = df1[yval] - df1["exp"]
    df1 = df1.drop([yval,"exp"], axis=1)
    return df1

def ols(df, xdt, yval):
    df1 = df[[xdt,yval]].copy()
    mindt = df1[df1[yval].notnull()][xdt].min()
    df1 = df1[df1[xdt] >= mindt]
    bd = pd.Timestamp('2000-01-01')
    X = df1[xdt].values
    Y = df1[yval].values
    numer, denom, n, ex, ey = 0, 0, 0, 0, 0
    for i in range(len(X)):
        if math.isnan(Y[i]) == False:
            n += 1
            x = pd.Timestamp(X[i]) - bd
            ex += x.days
            ey += Y[i]
    ex = ex/n
    ey = ey/n
    for i in range(len(X)):
        if math.isnan(Y[i]) == False:
            x = pd.Timestamp(X[i]) - bd
            numer += (x.days - ex) * (Y[i] - ey)
            denom += (x.days - ex) ** 2
    m = numer / denom
    c = ey - (m * ex)
    df1["value"] = df1.apply(lambda row: (pd.Timestamp(row[xdt]) - bd).days * m + c, axis=1)
    df1 = df1.drop([yval], axis=1)
    return df1

def plotly_bar(df, xval, yvals,tval, mode, orient, rangescale):
    if yvals is None:
        return px.bar(df, x=xval, title=tval)   # CHANGED: was .show() (opens a browser); return the figure
    dfout = pd.DataFrame()
    ymin, ymax = 9999999, -9999999
    for yval in yvals.split(","):
        dft = df[[xval,yval]].copy()
        dft = dft.rename({yval:'value'}, axis=1)
        dft["ytype"] = yval
        ymin = min(ymin,dft["value"].min())
        ymax = max(ymax,dft["value"].max())
        dfout = pd.concat([dfout,dft])
    if orient == 'v':
        fig = px.bar(dfout, x=xval, y="value", color="ytype", title=tval, barmode=mode, orientation=orient)
    else:
        fig = px.bar(dfout, x='value', y=xval, color="ytype", title=tval, barmode=mode, orientation=orient)
    if rangescale:
        fig.update_yaxes(range=[ymin, ymax])
    return fig

class graph:

    @staticmethod
    def heatmap(df, grp, key, value, color):
        #df = px.data.gapminder().query("year == 2007")
        fig = px.treemap(df, path=[px.Constant("All"), grp, key], values=value,
                  color=color, hover_data=[key], color_continuous_scale='rdylgn',
                  color_continuous_midpoint=np.average(df[color], weights=df[value]))
        fig.data[0].customdata = fig.data[0].marker.colors
        fig.data[0].texttemplate = "%{label}<br>%{customdata:.2f}"
        #fig.data[0].textinfo = 'customdata'
        #label+text+value+current path
        #fig.layout.hovermode = False
        fig.update_layout(margin = dict(t=50, l=25, r=25, b=25))
        return fig

    @staticmethod
    def line(df,xval,yvals,tval="Multi-line plot"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[xval,yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"] = yval
            dfout = pd.concat([dfout,dft])
        fig = px.line(dfout, x=xval, y="value", color="ytype", line_group="ytype", title=tval)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def residue(df,xdate,yvals,tval="Multi-line plot with trend residue"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[xdate,yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"]  = yval
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
            dft = residue(df, xdate, yval)
            dft["ytype"]  = yval + "_residue"
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
        fig = px.line(dfout, x=xdate, y="value", color="ygroup", line_group="ytype", title=tval)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def scatter(df, xval, yval, tval="Scatter plot"):
        df['color'] = 'hist'
        df['size'] = 1
        df.loc[df.tail(1).index,'color'] = 'spot'
        df.loc[df.tail(1).index,'size'] = 5
        fig = px.scatter(df, x=xval , y=yval, size='size', color='color',trendline='lowess', title=tval)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def trend(df,xdate,yvals,tval="Multi-line plot with trend"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[xdate,yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"]  = yval
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
            dft = ols(df, xdate, yval)
            dft["ytype"]  = yval + "_trend"
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
        fig = px.line(dfout, x=xdate, y="value", color="ygroup", line_group="ytype", title=tval)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        ## fig.update_layout(yaxis_range=[-4,4])
        return fig

    @staticmethod
    def mvavg(df,xdate,yvals,win=5,tval="Multi-line plot with moving average"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[xdate,yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"]  = yval
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
            dft = mvavg(df, xdate, yval, win)
            dft["ytype"]  = yval + "_mvavg"
            dft["ygroup"] = yval
            dfout = pd.concat([dfout,dft])
        fig = px.line(dfout, x=xdate, y="value", color="ygroup", line_group="ytype", title=tval)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    #values from multiple yvals are stacked over each other
    @staticmethod
    def hist_stack(df,yvals,tval="Multi-hist stacked plot"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"] = yval
            dfout = pd.concat([dfout,dft])
        fig = px.histogram(dfout, x="value", color="ytype")
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def hist(df,yvals,tval="Multi-hist plot"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"] = yval
            dfout = pd.concat([dfout,dft])
        cmode = { yvals.split(",")[0]:'blue',yvals.split(",")[1]:'red'}
        fig = px.histogram(dfout, x="value", color="ytype", color_discrete_map=cmode, barmode="overlay")
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def hist_cum(df,yvals,tval="Multi-hist cumulative plot"):
        dfout = pd.DataFrame()
        for yval in yvals.split(","):
            dft = df[[yval]].copy()
            dft = dft.rename({yval:'value'}, axis=1)
            dft["ytype"] = yval
            dfout = pd.concat([dfout,dft])
        #fig.update_traces(opacity=0.75)
        fig = px.histogram(dfout, x="value", color="ytype", barmode="overlay", cumulative_enabled=True)
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def ntile_plot(df, colname):
        dict = { 'index':[] }
        for col in colname.split(","):
            dict[col] = []
        for i in range(101):
            dict['index'].append(i)
            for col in colname.split(","):
                dict[col].append(df[col].quantile(i/100.0))
        fig = graph.line(pd.DataFrame(dict), "index", colname, "Percentile line plot")
        fig.update_xaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_yaxes(showgrid=True,gridwidth=1,gridcolor='#CCCCCC',showspikes=True)
        fig.update_traces(hovertemplate='%{x}<br>%{y}<extra></extra>')
        fig.update_layout(hovermode='closest') #x,y,closest,False,x unified,y unified
        return fig

    @staticmethod
    def table(df):
        dfvals = []
        vals = list(df.columns)

        for val in vals:
            if df[val].dtype == 'float64':
                df[val] = df[val].map("{:,.2f}".format)
            dfvals.append(df[val])

        return go.Figure(data=[go.Table(
            header=dict(values=vals, fill_color="paleturquoise", align="left"),
            cells=dict(values=dfvals, fill_color="lavender", align="left"))
        ])

    @staticmethod
    def table1(df):
        dfvals = []
        vals = list(df.columns)
        for val in vals: dfvals.append(df[val])
        return go.Figure(data=[go.Table(
            header=dict(values=vals, fill_color="paleturquoise", align="left"),
            cells=dict(values=dfvals, fill_color="lavender", align="left"))
        ])

    @staticmethod
    def hbar_group(df,xval,yvals=None,tval="Plotly group hbar", rangescale=False):
        return plotly_bar(df, xval, yvals, tval, mode='group', orient='h', rangescale=rangescale)

    @staticmethod
    def hbar_stack(df,xval,yvals=None,tval="Plotly stacked hbar", rangescale=False):
        return plotly_bar(df, xval, yvals, tval, mode='stack', orient='h', rangescale=rangescale)

    @staticmethod
    def bar_stack(df,xval,yvals=None,tval="Plotly stack bar", rangescale=False):
        return plotly_bar(df, xval, yvals, tval, mode='stack', orient='v', rangescale=rangescale)

    @staticmethod
    def bar_group(df,xval,yvals=None,tval="Plotly group bar", rangescale=False):
        return plotly_bar(df, xval, yvals, tval, mode='group', orient='v', rangescale=rangescale)
