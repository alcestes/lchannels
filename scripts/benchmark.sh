#!/bin/sh

default_repetitions=1
default_msgcnt=20000

if [ -z "$1" ]; then
    repetitions=$default_repetitions
else
    repetitions="$1"
fi

if [ -z "$2" ]; then
    msgcnt=$default_msgcnt
else
    msgcnt="$2"
fi

# Increase the heap size, if a "serious" benchmark is being launched
if [ $msgcnt -gt $default_msgcnt ]; then
    heap="4g"
else
    heap="512m"
fi

for rep in `seq 1 $repetitions`; do
    echo "*** Benchmarking: JVM instantiation $rep/$repetitions"
    mkdir -p bench/$rep; # Create benchmarking directories
    sbt -J-Xms$heap -J-Xmx$heap "project benchmarks" "run bench/$rep $msgcnt"
    echo
done

echo "*** Concatenating benchmark results..."
cp bench/1/* .

for rep in `seq 2 $repetitions`; do
    for f in `ls bench/$rep/*`; do
	tail -n +3 $f >> `basename $f`
    done
done

echo "*** Generating plots (in PDF format)..."
for f in *.csv; do
    pdfplot="`basename $f .csv`.pdf"
    echo "    $f -> $pdfplot"
    ./scripts/plot-benchmark.py $f $pdfplot
done
