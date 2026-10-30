package com.foxhole.core.profile

import com.foxhole.core.importer.exportPortableConfigs

fun exportPortableProfileConfigs(profileName: String, configs: List<String>): String =
    exportPortableConfigs(profileName, configs)
