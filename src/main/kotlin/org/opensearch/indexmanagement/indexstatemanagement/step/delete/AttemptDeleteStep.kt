package org.opensearch.indexmanagement.indexstatemanagement.step.delete

import org.apache.logging.log4j.LogManager
import org.opensearch.ExceptionsHelper
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.indexmanagement.opensearchapi.suspendUntil
import org.opensearch.indexmanagement.spi.indexstatemanagement.Step
import org.opensearch.indexmanagement.spi.indexstatemanagement.metrics.IndexManagementActionsMetrics
import org.opensearch.indexmanagement.spi.indexstatemanagement.metrics.actionmetrics.DeleteActionMetrics
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.StepMetaData
import org.opensearch.snapshots.SnapshotInProgressException
import org.opensearch.telemetry.metrics.tags.Tags
import org.opensearch.transport.RemoteTransportException

class AttemptDeleteStep : Step(name) {
    private val logger = LogManager.getLogger(javaClass)
    private var stepStatus = StepStatus.STARTING
    private var info: Map<String, Any>? = null
    private lateinit var indexManagementActionsMetrics: IndexManagementActionsMetrics
    private lateinit var actionMetrics: DeleteActionMetrics

    override suspend fun execute(indexManagementActionMetrics: IndexManagementActionsMetrics): Step {
        val context = this.context ?: return this
        val indexName = context.metadata.index
        this.indexManagementActionsMetrics = indexManagementActionMetrics
        this.actionMetrics = indexManagementActionMetrics.getActionMetrics(IndexManagementActionsMetrics.DELETE) as DeleteActionMetrics

        try {
            val response: AcknowledgedResponse =
                context.client.admin().indices()
                    .suspendUntil { delete(DeleteIndexRequest(indexName), it) }

            if (response.isAcknowledged) {
                stepStatus = StepStatus.COMPLETED
                info = mapOf("message" to getSuccessMessage(indexName))
                actionMetrics.successes.add(
                    1.0,
                    Tags.create().addTag("index_name", context.metadata.index)
                        .addTag("policy_id", context.metadata.policyID).addTag("node_id", context.clusterService.nodeName),
                )
            } else {
                val message = getFailedMessage(indexName)
                logger.warn(message)
                stepStatus = StepStatus.FAILED
                info = mapOf("message" to message)
                actionMetrics.failures.add(
                    1.0,
                    Tags.create().addTag("index_name", context.metadata.index)
                        .addTag("policy_id", context.metadata.policyID).addTag("node_id", context.clusterService.nodeName),
                )
            }
        } catch (e: RemoteTransportException) {
            val cause = ExceptionsHelper.unwrapCause(e)
            if (cause is SnapshotInProgressException) {
                handleSnapshotException(indexName, cause)
            } else {
                handleException(indexName, cause as Exception)
            }
        } catch (e: SnapshotInProgressException) {
            handleSnapshotException(indexName, e)
        } catch (e: Exception) {
            handleException(indexName, e)
        }

        return this
    }

    private fun handleSnapshotException(indexName: String, e: SnapshotInProgressException) {
        val message = getSnapshotMessage(indexName)
        logger.warn(message, e)
        stepStatus = StepStatus.CONDITION_NOT_MET
        info = mapOf("message" to message)
    }

    private fun handleException(indexName: String, e: Exception) {
        val message = getFailedMessage(indexName)
        logger.error(message, e)
        stepStatus = StepStatus.FAILED
        actionMetrics.failures.add(
            1.0,
            Tags.create().addTag("index_name", context?.metadata?.index)
                .addTag("policy_id", context?.metadata?.policyID).addTag("node_id", context?.clusterService?.nodeName),
        )
        val mutableInfo = mutableMapOf("message" to message)
        val errorMessage = e.message
        if (errorMessage != null) mutableInfo["cause"] = errorMessage
        info = mutableInfo.toMap()
    }

    override fun getUpdatedManagedIndexMetadata(currentMetadata: ManagedIndexMetaData): ManagedIndexMetaData {
        return currentMetadata.copy(
            stepMetaData = StepMetaData(name, getStepStartTime(currentMetadata).toEpochMilli(), stepStatus),
            transitionTo = null,
            info = info,
        )
    }

    override fun isIdempotent() = true

    companion object {
        const val name = "attempt_delete"

        fun getFailedMessage(indexName: String) = "Failed to delete index [index=$indexName]"

        fun getSuccessMessage(indexName: String) = "Successfully deleted index [index=$indexName]"

        fun getSnapshotMessage(indexName: String) = "Index had snapshot in progress, retrying deletion [index=$indexName]"
    }
}
