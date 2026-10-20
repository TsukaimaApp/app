package labs.suchattai.tsukaima.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthDto(
    val ok: Boolean? = null,
    val version: String? = null,
    @SerialName("auth_required") val authRequired: Boolean? = null,
)

@Serializable
data class DiskDto(
    @SerialName("total_mb") val totalMb: Double? = null,
    @SerialName("free_mb") val freeMb: Double? = null,
    @SerialName("used_percent") val usedPercent: Double? = null,
)

@Serializable
data class StatusDto(
    val version: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean? = null,
    @SerialName("gateway_state") val gatewayState: String? = null,
    @SerialName("gateway_updated_at") val gatewayUpdatedAt: String? = null,
    @SerialName("gateway_platforms") val gatewayPlatforms: Map<String, JsonElement> = emptyMap(),
    @SerialName("active_sessions") val activeSessions: Int? = null,
    @SerialName("active_agents") val activeAgents: Int? = null,
    val profiles: List<JsonElement> = emptyList(),
    val disk: DiskDto? = null,
    val memory: JsonObject? = null,
    val overall: String? = null,
)

@Serializable
data class SystemStatsDto(
    val hostname: String? = null,
    val os: String? = null,
    @SerialName("hermes_version") val hermesVersion: String? = null,
    @SerialName("cpu_count") val cpuCount: Int? = null,
    val memory: JsonObject? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val source: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val model: String? = null,
    val archived: Boolean = false,
    val profile: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    /** Search hits only: the matching text with `>>>` / `<<<` around the match. */
    val snippet: String? = null,
) {
    /** A scheduled job's run (`source: "cron"`, id `cron_<job>_<time>`), listed apart from chats. */
    val isCron: Boolean get() = source == CRON_SOURCE
}

@Serializable
data class ProviderDto(
    val slug: String,
    val name: String? = null,
    val models: List<String> = emptyList(),
    @SerialName("is_current") val isCurrent: Boolean? = null,
    val authenticated: Boolean? = null,
)

@Serializable
data class ModelOptionsDto(
    val providers: List<ProviderDto> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class WorkspacesDto(
    val projects: List<JsonObject> = emptyList(),
    val repos: List<JsonObject> = emptyList(),
    @SerialName("default_cwd") val defaultCwd: String? = null,
    val home: String? = null,
)

@Serializable
data class CronScheduleDto(
    val kind: String? = null,
    val expr: String? = null,
    val display: String? = null,
)

@Serializable
data class CronJobDto(
    val id: String,
    val name: String? = null,
    val prompt: String? = null,
    val schedule: CronScheduleDto? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean? = null,
    val state: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    val deliver: JsonElement? = null,
)

@Serializable
data class DeliveryTargetDto(
    val id: String,
    val name: String? = null,
)

@Serializable
data class PairingDto(
    val pending: List<JsonObject> = emptyList(),
    val approved: List<JsonObject> = emptyList(),
)

@Serializable
data class SkillDto(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class ToolsetDto(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val available: Boolean? = null,
)

@Serializable
data class ProfileDto(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean? = null,
)

@Serializable
data class ProfilesDto(val profiles: List<ProfileDto> = emptyList())

@Serializable
data class LogsDto(
    val file: String? = null,
    val lines: List<String> = emptyList(),
)

@Serializable
data class UsageDto(
    val daily: List<JsonObject> = emptyList(),
    @SerialName("by_model") val byModel: List<JsonObject> = emptyList(),
    val totals: JsonObject? = null,
)

@Serializable
data class AuthMeDto(
    @SerialName("user_id") val userId: String? = null,
    val provider: String? = null,
    @SerialName("expires_at") val expiresAt: Long = 0L,
)

/** `SessionDto.source` of a cron job's run. */
const val CRON_SOURCE = "cron"
