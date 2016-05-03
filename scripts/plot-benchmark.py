#!/usr/bin/env python

import matplotlib.pyplot as plotlib
import numpy
import sys

DELIMITER = ','

def makePlot(infile, outfile):
    import matplotlib
    matplotlib.rcParams.update({'font.size': 12})
    
    (title, headers) = readTitleAndHeaders(infile)
    
    data = numpy.genfromtxt(infile,
                            delimiter=DELIMITER,
                            skip_header=1, # Skip benchmark title
                            dtype=numpy.long) / 1000000.0

    box_colours = ['ForestGreen', 'SkyBlue', 'Tan', 'Plum', 'ForestGreen', 'Maroon', 'ForestGreen']

    locations = range(1, len(headers) + 1)

    fig = plotlib.figure()
    plot = plotlib.boxplot(data, widths=0.7, notch=True, positions=locations,
                           patch_artist=True, 
                           sym='') # Do not print outliers
    
    for box, colour in zip(plot['boxes'], box_colours):
        plotlib.setp(box, #color='DarkMagenta', 
                     linewidth=1, 
                     facecolor=colour)

    # plotlib.setp(plot['whiskers'], color='DarkMagenta', linewidth=1)
    # plotlib.setp(plot['caps'], color='DarkMagenta', linewidth=1)
    # plotlib.setp(plot['fliers'], color='OrangeRed', marker='o', markersize=3)
    # plotlib.setp(plot['medians'], color='OrangeRed', linewidth=1)
    
    plotlib.grid(axis='y',          # set y-axis grid lines
                 linestyle='--',     # use dashed lines
                 which='major',      # only major ticks
                 color='lightgrey',  # line colour
                 alpha=0.8)          # make lines semi-translucent

    plotlib.xticks(locations,     # tick marks
                   headers,       # labels
                   rotation=25)   # rotate the labels
    
    plotlib.ylabel('milliseconds')    # y-axis label
    plotlib.title(title, fontsize=12, fontweight='bold') # plot title
    
    # plotlib.show()                          # render the plot
    fig.savefig(outfile, bbox_inches='tight')

def readTitleAndHeaders(infile):
    f = open(infile)
    title = f.readline()
    headers = map(lambda x: x.replace(' ', "\n"),
                  f.readline().strip().split(DELIMITER))
    f.close()
    return (title, headers)

if (__name__ == '__main__'):
    infile = sys.argv[1]
    outfile = sys.argv[2]
    makePlot(infile, outfile)
