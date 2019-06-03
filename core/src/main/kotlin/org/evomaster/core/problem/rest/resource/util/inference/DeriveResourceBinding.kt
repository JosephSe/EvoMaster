package org.evomaster.core.problem.rest.resource.util.inference

import org.evomaster.core.database.DbAction
import org.evomaster.core.database.schema.Table
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.resource.model.PathRToken
import org.evomaster.core.problem.rest.resource.model.RestResourceCalls
import org.evomaster.core.problem.rest.resource.model.RestResourceNode
import org.evomaster.core.problem.rest.resource.model.dependency.PostCreationChain
import org.evomaster.core.problem.rest.resource.util.inference.model.MatchedInfo
import org.evomaster.core.problem.rest.resource.util.inference.model.ParamGeneBindMap

interface DeriveResourceBinding {

    fun deriveResourceToTable(resourceCluster: MutableList<RestResourceNode>, allTables : Map<String, Table>)
    /**
     * derive relationship between a resource and a table
     */
    fun deriveResourceToTable(resourceNode : RestResourceNode, allTables : Map<String, Table>)

    fun generateRelatedTables(calls: RestResourceCalls, dbActions : MutableList<DbAction>) :  MutableMap<RestAction, MutableList<ParamGeneBindMap>>? = null

    fun generateRelatedTables(ar: RestResourceNode) :  MutableMap<String, MutableSet<String>>? = null
}