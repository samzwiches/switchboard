package com.switchboard.core.actions

import com.switchboard.providers.api.StructuredAction

interface ActionHandler {
    val supportedActionNames: Set<String>
    suspend fun handle(action: StructuredAction): ActionResult
}

interface ActionRouter {
    suspend fun route(actions: List<StructuredAction>): List<ActionResult>
}

data class ActionResult(
    val action: StructuredAction,
    val status: Status,
    val message: String,
) {
    enum class Status { HANDLED, UNSUPPORTED, FAILED }
}

class DefaultActionRouter(
    private val handlers: List<ActionHandler>,
) : ActionRouter {
    override suspend fun route(actions: List<StructuredAction>): List<ActionResult> =
        actions.map { action ->
            val handler = handlers.firstOrNull { action.name in it.supportedActionNames }
            if (handler == null) {
                ActionResult(action, ActionResult.Status.UNSUPPORTED, "No handler for ${action.name}")
            } else {
                runCatching { handler.handle(action) }
                    .getOrElse { error ->
                        ActionResult(
                            action,
                            ActionResult.Status.FAILED,
                            error.message ?: "Action failed",
                        )
                    }
            }
        }
}

class MockActionHandler : ActionHandler {
    override val supportedActionNames: Set<String> = setOf("mock.echo")

    override suspend fun handle(action: StructuredAction): ActionResult = ActionResult(
        action = action,
        status = ActionResult.Status.HANDLED,
        message = action.parameters["value"] ?: "Mock action handled",
    )
}

