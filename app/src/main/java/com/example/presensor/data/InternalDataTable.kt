package com.example.presensor.data

data class InternalDataTable(
    val headers: List<String>,
    val rows: List<List<String>>
) {
    fun getCellValue(rowIndex: Int, columnName: String): String {
        val colIndex = headers.indexOfFirst { it.equals(columnName, ignoreCase = true) }
        if (colIndex == -1) return ""
        return rows.getOrNull(rowIndex)?.getOrNull(colIndex) ?: ""
    }

    fun getCellValue(rowIndex: Int, colIndex: Int): String {
        return rows.getOrNull(rowIndex)?.getOrNull(colIndex) ?: ""
    }

    val rowCount: Int get() = rows.size
    
    /**
     * Returns the full grid including headers as the first row. 
     * Useful for operations that need to rewrite the whole sheet.
     */
    fun toFullGrid(): List<List<String>> {
        val grid = mutableListOf<List<String>>()
        grid.add(headers)
        grid.addAll(rows)
        return grid
    }
}
