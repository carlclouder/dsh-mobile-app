package dev.dshmobile.model

import kotlinx.serialization.Serializable

/**
 * 模型目录（需求：会话页模型/推理等级选择器）。
 * 结构与 dsh-host-apiproxy/sessions.schema.js 的 sessionModelsValueSchema 一致。
 */
@Serializable
data class ModelDirectory(
    val current: ModelSelection? = null,
    val routable: Boolean = false,
    val groups: List<ModelProviderGroup> = emptyList(),
    val failures: List<ModelCatalogFailure> = emptyList(),
)

/** 当前/选定的模型选择。 */
@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/** 一个供应商组。 */
@Serializable
data class ModelProviderGroup(
    val id: String,
    val name: String,
    val models: List<ModelCatalogModel> = emptyList(),
)

/** 一个模型（带可选推理等级元数据）。 */
@Serializable
data class ModelCatalogModel(
    val id: String,
    val name: String,
    val description: String? = null,
    val reasoning: ModelReasoning? = null,
)

/** 模型的推理等级元数据。 */
@Serializable
data class ModelReasoning(
    val efforts: List<ModelReasoningEffort> = emptyList(),
    val defaultEffort: String? = null,
)

@Serializable
data class ModelReasoningEffort(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class ModelCatalogFailure(
    val id: String,
    val name: String,
    val message: String,
)
