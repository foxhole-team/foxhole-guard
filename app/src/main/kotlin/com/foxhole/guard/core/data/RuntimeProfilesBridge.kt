package com.foxhole.guard.core.data

import com.foxhole.core.runtime.RuntimeProfiles

fun ProfileRepository.asRuntimeProfiles(): RuntimeProfiles = ProfileRepositoryRuntimeProfiles(this)
