package com.foxhole.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = InterfaceModeSerializer::class)
enum class InterfaceMode { USER, EXPERT }

internal object InterfaceModeSerializer : KSerializer<InterfaceMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.foxhole.core.model.InterfaceMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InterfaceMode =
        when (decoder.decodeString()) {
            "USER" -> InterfaceMode.USER
            else -> InterfaceMode.EXPERT
        }

    override fun serialize(encoder: Encoder, value: InterfaceMode) = encoder.encodeString(value.name)
}
