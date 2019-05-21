package org.evomaster.core.database

import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto
import org.evomaster.client.java.controller.api.dto.database.operations.DatabaseCommandDto
import org.evomaster.client.java.controller.api.dto.database.operations.QueryResultDto
import org.evomaster.client.java.controller.api.dto.database.schema.DatabaseType
import org.evomaster.client.java.controller.api.dto.database.schema.DbSchemaDto
import org.evomaster.client.java.controller.api.dto.database.schema.TableDto
import org.evomaster.core.database.schema.Column
import org.evomaster.core.database.schema.ColumnDataType
import org.evomaster.core.database.schema.ForeignKey
import org.evomaster.core.database.schema.Table
import org.evomaster.core.problem.rest.resource.db.SQLGenerator
import org.evomaster.core.problem.rest.resource.db.SQLKey
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.ImmutableDataHolderGene
import org.evomaster.core.search.gene.SqlAutoIncrementGene
import org.evomaster.core.search.gene.SqlPrimaryKeyGene
import org.evomaster.dbconstraint.*


class SqlInsertBuilder(
        schemaDto: DbSchemaDto,
        private val dbExecutor: DatabaseExecutor? = null
) {

    /**
     * The counter used to give unique IDs to the DbActions.
     * Should not be negative
     */
    private var counter: Long = 0

    /*
        All the objects here are immutable
     */
    private val tables = mutableMapOf<String, Table>()

    private val databaseType: DatabaseType

    private val name: String


    init {
        /*
            Here, we need to transform (and validate) the input DTO
            into immutable domain objects
         */
        if (counter < 0) {
            throw IllegalArgumentException("Invalid negative counter: $counter")
        }

        if (schemaDto.databaseType == null) {
            throw IllegalArgumentException("Undefined database type")
        }
        if (schemaDto.name == null) {
            throw IllegalArgumentException("Undefined schema name")
        }

        databaseType = schemaDto.databaseType
        name = schemaDto.name

        val tableToColumns = mutableMapOf<String, MutableSet<Column>>()
        val tableToForeignKeys = mutableMapOf<String, MutableSet<ForeignKey>>()

        for (t in schemaDto.tables) {

            val tableConstraints = parseTableConstraints(t)

            val columns = mutableSetOf<Column>()

            for (c in t.columns) {

                if (!c.table.equals(t.name, ignoreCase = true)) {
                    throw IllegalArgumentException("Column in different table: ${c.table}!=${t.name}")
                }


                val lowerBound = findLowerBound(tableConstraints, c.name)
                val upperBound = findUpperBound(tableConstraints, c.name)
                val enumValuesAsStrings = findEnumValues(tableConstraints, c.name)

                val column = Column(
                        name = c.name,
                        size = c.size,
                        type = ColumnDataType.valueOf(c.type.toUpperCase()),
                        primaryKey = c.primaryKey,
                        autoIncrement = c.autoIncrement,
                        foreignKeyToAutoIncrement = c.foreignKeyToAutoIncrement,
                        nullable = c.nullable,
                        unique = c.unique,
                        lowerBound = lowerBound,
                        upperBound = upperBound,
                        enumValuesAsStrings = enumValuesAsStrings
                )

                columns.add(column)
            }

            tableToColumns[t.name] = columns
        }

        for (t in schemaDto.tables) {

            val fks = mutableSetOf<ForeignKey>()

            for (f in t.foreignKeys) {

                tableToColumns[f.targetTable]
                        ?: throw IllegalArgumentException("Foreign key for non-existent table ${f.targetTable}")

                val sourceColumns = mutableSetOf<Column>()


                for (cname in f.sourceColumns) {
                    //TODO wrong check, as should be based on targetColumns, when we ll introduce them
                    //val c = targetTable.find { it.name.equals(cname, ignoreCase = true) }
                    //        ?: throw IllegalArgumentException("Issue in foreign key: table ${f.targetTable} does not have a column called $cname")

                    val c = tableToColumns[t.name]!!.find { it.name.equals(cname, ignoreCase = true) }
                            ?: throw IllegalArgumentException("Issue in foreign key: table ${t.name} does not have a column called $cname")
                    sourceColumns.add(c)
                }

                fks.add(ForeignKey(sourceColumns, f.targetTable))
            }

            tableToForeignKeys[t.name] = fks
        }

        for (t in schemaDto.tables) {
            val table = Table(t.name, tableToColumns[t.name]!!, tableToForeignKeys[t.name]!!)
            tables[t.name] = table
        }
    }

    private fun findRangeConstraint(tableConstraints: List<TableConstraint>, columnName: String): RangeConstraint? {
        return tableConstraints.filterIsInstance<RangeConstraint>()
                .firstOrNull { c -> c.columnName.equals(columnName, true) }
    }

    private fun findLowerBound(tableConstraints: List<TableConstraint>, columnName: String): Int? {
        val rangeConstraint = findRangeConstraint(tableConstraints, columnName)
        if (rangeConstraint != null) {
            return rangeConstraint.minValue.toInt()
        }

        val lowerBounds = tableConstraints
                .asSequence()
                .filterIsInstance<LowerBoundConstraint>()
                .filter { c -> c.columnName.equals(columnName, true) }
                .map { c -> c.lowerBound.toInt() }
                .toList()

        return if (lowerBounds.isNotEmpty())
            lowerBounds.max()
        else
            null

    }

    private fun findUpperBound(tableConstraints: List<TableConstraint>, columnName: String): Int? {
        val rangeConstraint = findRangeConstraint(tableConstraints, columnName)
        if (rangeConstraint != null) {
            return rangeConstraint.maxValue.toInt()
        }

        val upperBounds = tableConstraints
                .asSequence()
                .filterIsInstance<UpperBoundConstraint>()
                .filter { c -> c.columnName.equals(columnName, true) }
                .map { c -> c.upperBound.toInt() }
                .toList()

        return if (upperBounds.isNotEmpty())
            upperBounds.min()
        else
            null

    }

    private fun findEnumValues(tableConstraints: List<TableConstraint>, columnName: String): List<String>? {
        val enumValues = tableConstraints
                .filter { c -> c is EnumConstraint }
                .map { c -> c as EnumConstraint }
                .filter { c -> c.columnName.equals(columnName, true) }
                .map { c -> c.valuesAsStrings }
                .firstOrNull()

        return enumValues
    }


    private fun parseTableConstraints(t: TableDto): List<TableConstraint> {
        val tableConstraints = mutableListOf<TableConstraint>()
        val tableName = t.name

        for (sqlCheckExpression in t.tableCheckExpressions) {
            val builder = ConstraintBuilder()
            val tableConstraint = builder.translateToConstraint(tableName, sqlCheckExpression.sqlCheckExpression)
            tableConstraints.add(tableConstraint)
        }
        return tableConstraints
    }

    private fun getTable(tableName: String): Table {
        return tables[tableName]
                ?: tables[tableName.toUpperCase()]
                ?: throw IllegalArgumentException("No table called $tableName")
    }

    /**
     * Create a SQL insertion operation into the table called [tableName].
     * Use columns only from [columnNames], to avoid wasting resources in setting
     * non-used data.
     * Note: due to constraints (eg non-null), we might create data also for non-specified columns.
     *
     * If the table has non-null foreign keys to other tables, then create an insertion for those
     * as well. This means that more than one action can be returned here.
     *
     * Note: the created actions have default values. You might want to randomize its data before
     * using it.
     *
     * Note: names are case-sensitive, although some DBs are case-insensitive. To make
     * things even harder, there could be a mismatch in casing when inserting and then
     * reading back table/column names from schema :(
     * This is (should not) be a problem when running EM, but can be trickier when writing
     * test cases manually for EM
     */
    fun createSqlInsertionAction(tableName: String, columnNames: Set<String>): List<DbAction> {

        val table = getTable(tableName)

        val takeAll = columnNames.contains("*")

        if (takeAll && columnNames.size > 1) {
            throw IllegalArgumentException("Invalid column description: more than one entry when using '*'")
        }

        for (cn in columnNames) {
            if (cn != "*" && !table.columns.any { it.name.equals(cn, true) }) {
                throw IllegalArgumentException("No column called $cn in table $tableName")
            }
        }

        val selectedColumns = mutableSetOf<Column>()

        for (c in table.columns) {
            /*
                we need to take primaryKey even if autoIncrement.
                Point is, even if value will be set by DB, and so could skip it,
                we would still need a non-modifiable, non-printable Gene to
                store it, as we can have other Foreign Key genes pointing to it
             */

            if (takeAll || columnNames.any { it.equals(c.name, true) } || !c.nullable || c.primaryKey) {
                //TODO are there also other constraints to consider?
                selectedColumns.add(c)
            }
        }

        val insertion = DbAction(table, selectedColumns, counter++)
        val actions = mutableListOf(insertion)

        for (fk in table.foreignKeys) {
            /*
                Assumption: in a valid Schema, this should never end up
                in a infinite loop?
             */
            val pre = createSqlInsertionAction(fk.targetTable, setOf())
            actions.addAll(0, pre)
        }

        return actions
    }


    /**
     * Check current state of database.
     * For each row, create a DbAction containing only Primary Keys
     * and immutable data
     */
    fun extractExistingPKs(): List<DbAction> {

        if (dbExecutor == null) {
            throw IllegalStateException("No Database Executor registered for this object")
        }

        val list = mutableListOf<DbAction>()

        for (table in tables.values) {

            val pks = table.columns.filter { it.primaryKey }

            if (pks.isEmpty()) {
                /*
                    In some very special cases, it might happen that a table has no defined
                    primary key. It's rare, but it is technically legal.
                    We can just skip those tables.
                 */
                continue
            }

            val sql = "SELECT ${pks.map { "\"${it.name}\"" }.joinToString(",")} FROM \"${table.name}\""

            val dto = DatabaseCommandDto()
            dto.command = sql

            val result: QueryResultDto = dbExecutor.executeDatabaseCommandAndGetQueryResults(dto)
                    ?: continue

            result.rows.forEach { r ->

                val id = counter++

                val genes = mutableListOf<Gene>()

                for (i in 0 until pks.size) {
                    val pkName = pks[i].name
                    val inQuotes = pks[i].type.shouldBePrintedInQuotes()
                    val data = ImmutableDataHolderGene(pkName, r.columnData[i], inQuotes)
                    val pk = SqlPrimaryKeyGene(pkName, table.name, data, id)
                    genes.add(pk)
                }

                val action = DbAction(table, pks.toSet(), id, genes, true)
                list.add(action)
            }
        }

        return list
    }


    /**
     * the function is used to execute a select sql constrained by specified [pkValues]
     * @return DbAction has all values of columns in the row regarding [pkValues]
     *
     * Note that [pkValues] only points to one row.
     */
    fun extractExistingByCols(tableName: String, pkValues : DataRowDto): DbAction{

        if(dbExecutor == null){
            throw IllegalStateException("No Database Executor registered for this object")
        }

        val table = tables.values.find { it.name.toLowerCase() == tableName.toLowerCase() }
                ?: throw  IllegalArgumentException("cannot find the table by name $tableName")


        val pks = table.columns.filter { it.primaryKey }
        val cols = table.columns.toList()

        var row : DataRowDto? = null
        if(pks.isNotEmpty()){


            val condition = SQLGenerator.composeAndConditions(
                    SQLGenerator.genConditions(
                            pks.map { it.name }.toTypedArray(),
                            pkValues.columnData,
                            table)
            )

            val sql = SQLGenerator.genSelect(cols.map { it.name }.toTypedArray(),table, condition)

            val dto = DatabaseCommandDto()
            dto.command = sql

            val result : QueryResultDto = dbExecutor.executeDatabaseCommandAndGetQueryResults(dto)
                    ?: throw IllegalArgumentException("rows regarding pks can not be found")
            if(result.rows.size != 1){
                throw IllegalArgumentException("the size of rows regarding pks is ${result.rows.size}, and except is 1")
            }
            row = result.rows.first()
        }else
            row = pkValues


        val id = counter++

        val genes = mutableListOf<Gene>()

        for(i in 0 until cols.size){

            if(row!!.columnData[i] != "NULL"){

                val colName= cols[i].name
                val inQuotes = cols[i].type.shouldBePrintedInQuotes()

                val gene = if(cols[i].primaryKey){
                    SqlPrimaryKeyGene(colName, table.name, ImmutableDataHolderGene(colName, row!!.columnData[i], inQuotes), id)
                }else{
                    ImmutableDataHolderGene(colName, row!!.columnData[i], inQuotes)
                }
                genes.add(gene)
            }
        }

        return DbAction(table, pks.toSet(), id, genes, true)

    }

    /**
     * @return a list of sql insertion, and each of insertion includes all columns of the table.
     *
     * Note that the function is quite similar with [createSqlInsertionAction], we may add some variables
     *      to insert an row with all columns into a reference table (by FK)
     */
    fun createSqlInsertionActionWithAllColumn(tableName: String): List<DbAction> {

        val table = getTable(tableName)
        val columnNames = table.columns.map { it.name }.toSet()

        val takeAll = columnNames.contains("*")

        if (takeAll && columnNames.size > 1) {
            throw IllegalArgumentException("Invalid column description: more than one entry when using '*'")
        }

        for (cn in columnNames) {
            if (cn != "*" && !table.columns.any { it.name == cn }) {
                throw IllegalArgumentException("No column called $cn in table $tableName")
            }
        }

        val selectedColumns = mutableSetOf<Column>()

        for (c in table.columns) {
            /*
                we need to take primaryKey even if autoIncrement.
                Point is, even if value will be set by DB, and so could skip it,
                we would still need a non-modifiable, non-printable Gene to
                store it, as we can have other Foreign Key genes pointing to it
             */

            if (takeAll || columnNames.contains(c.name) || !c.nullable || c.primaryKey) {
                //TODO are there also other constraints to consider?
                selectedColumns.add(c)
            }
        }

        val insertion = DbAction(table, selectedColumns, counter++)
        val actions = mutableListOf(insertion)

        for (fk in table.foreignKeys) {
            /*
                Assumption: in a valid Schema, this should never end up
                in a infinite loop?
             */
            val pre = createSqlInsertionActionWithAllColumn(fk.targetTable)
            actions.addAll(0, pre)
        }

        return actions
    }

    /**
     * get existing pks in db
     */
    fun extractExistingPKs(dataInDB : MutableMap<String, MutableList<DataRowDto>>, tablesMap : MutableMap<String, Table>? = null){

        if(dbExecutor == null){
            throw IllegalStateException("No Database Executor registered for this object")
        }

        dataInDB.clear()

        for(table in tables.values){
            val pks = table.columns.filter { it.primaryKey }
            val selected = if(pks.isEmpty()) {
                SQLKey.ALL.key
                //continue
            } else pks.map {"\"${it.name}\""}.joinToString(",")

            val sql = "SELECT $selected FROM \"${table.name}\""

            val dto = DatabaseCommandDto()
            dto.command = sql

            val result : QueryResultDto = dbExecutor.executeDatabaseCommandAndGetQueryResults(dto)
                    ?: continue
            dataInDB.getOrPut(table.name){ result.rows.map { it }.toMutableList()}
        }

        if(tablesMap!=null){
            tablesMap.clear()
            tablesMap.putAll(tables)
        }


    }

    fun generateSelect(sqlPks : List<SqlPrimaryKeyGene>) : MutableList<DbAction>{

        if(dbExecutor == null){
            throw IllegalStateException("No Database Executor registered for this object")
        }

        val involvedTables = sqlPks.map { it.tableName }
        val selection = mutableListOf<DbAction>()

        involvedTables.forEach { tableName ->
            val table = tables[tableName]!!
            val colMap = sqlPks
                    .filter { it.tableName == tableName }
                    .map { g -> Pair(table.columns.find {c -> c.name == g.name}!!, g.uniqueId) }.toMap()

            if(colMap.isNotEmpty()){
                val id = counter++
                selection.add(DbAction(
                        table,
                        colMap.keys.toHashSet(),
                        id,
                        colMap.map { SqlPrimaryKeyGene(
                                it.key.name,
                                tableName,
                                ImmutableDataHolderGene(it.key.name, it.value.toString(), it.key.type.shouldBePrintedInQuotes()),
                                it.value
                        ) },
                        true))
            }
        }


        return selection
    }

    fun getTableInfo(tableName: String) : Table?{
        return tables.values.find { it.name == tableName || it.name.toLowerCase() == tableName.toLowerCase() || it.name.toUpperCase() == tableName.toUpperCase() }
    }
}