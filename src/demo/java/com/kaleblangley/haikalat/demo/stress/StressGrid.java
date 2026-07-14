package com.kaleblangley.haikalat.demo.stress;

record StressGrid(int columns, int rows, int columnShift) {
    static StressGrid forInstances(int instances) {
        int minimumColumns = (int) Math.ceil(Math.sqrt(instances));
        int columns = 1;
        while (columns < minimumColumns) columns <<= 1;
        int rows = (instances + columns - 1) / columns;
        return new StressGrid(columns, rows, Integer.numberOfTrailingZeros(columns));
    }

    int columnMask() {
        return columns - 1;
    }
}
