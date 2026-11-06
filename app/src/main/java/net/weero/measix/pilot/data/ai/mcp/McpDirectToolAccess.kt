package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.EnterpriseMcpResource
import net.weero.measix.pilot.data.enterprise.PlatformAssistantMcpBinding
import net.weero.measix.pilot.data.enterprise.PlatformAssistantMcpBindingToolSelection
import net.weero.measix.pilot.data.enterprise.PlatformMcpDefinitionToolAccessMode
import net.weero.measix.pilot.data.enterprise.PlatformMcpToolGrant
import net.weero.measix.pilot.data.enterprise.PlatformMcpToolGrantApprovalPolicy

/** Pure projection of published rules onto the complete confirmed remote Tool object. */
enum class McpToolUnavailableReason {
    NOT_ALLOWED, NOT_SELECTED, MISSING, DIRECTORY_UNAVAILABLE,
}

internal data class McpToolAccess(
    val name: String,
    val tool: McpCatalogTool?,
    val needsApproval: Boolean,
    val unavailableReason: McpToolUnavailableReason? = null,
) {
    val enabled: Boolean get() = tool != null && unavailableReason == null
}

internal fun EnterpriseMcpResource.toolAccess(
    tools: List<McpCatalogTool>,
    binding: PlatformAssistantMcpBinding? = null,
): List<McpToolAccess> {
    val discovered = tools.associateBy { it.name }
    val grants = allowedTools.associateBy { it.name }
    val names = discovered.keys + grants.keys + binding?.toolNames.orEmpty()
    return names.map { name -> projectToolAccess(name, discovered[name], grants[name], binding) }
}

/** Invocation still checks the name when it has disappeared from both discovery and the grants. */
internal fun McpConnectionDefinition.ManagedPlatform.toolAccess(
    name: String,
    tools: List<McpCatalogTool>,
): McpToolAccess = toolAccess.projectToolAccess(
    name = name,
    tool = tools.firstOrNull { it.name == name },
    grant = toolAccess.allowedTools.firstOrNull { it.name == name },
    binding = assistantBinding,
)

private fun EnterpriseMcpResource.projectToolAccess(
    name: String,
    tool: McpCatalogTool?,
    grant: PlatformMcpToolGrant?,
    binding: PlatformAssistantMcpBinding?,
): McpToolAccess {
    val reason = when {
        binding?.toolSelection == PlatformAssistantMcpBindingToolSelection.ALLOWLIST && name !in binding.toolNames ->
            McpToolUnavailableReason.NOT_SELECTED
        toolAccessMode == PlatformMcpDefinitionToolAccessMode.ALLOWLIST && grant == null ->
            McpToolUnavailableReason.NOT_ALLOWED
        tool == null -> McpToolUnavailableReason.MISSING
        else -> null
    }
    return McpToolAccess(
        name = name,
        tool = tool,
        needsApproval = grant?.approvalPolicy == PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION,
        unavailableReason = reason,
    )
}

internal fun McpConnectionDefinition.toolAccess(tools: List<McpCatalogTool>): List<McpToolAccess> =
    if (this is McpConnectionDefinition.ManagedPlatform) toolAccess.toolAccess(tools, assistantBinding)
    else tools.map { tool ->
        val policy = toolPolicy(tool.name)
        McpToolAccess(tool.name, tool, policy?.needsApproval == true,
            if (policy?.enable == false) McpToolUnavailableReason.NOT_ALLOWED else null)
    }
