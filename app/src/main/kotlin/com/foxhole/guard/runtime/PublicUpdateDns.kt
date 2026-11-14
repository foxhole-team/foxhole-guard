package com.foxhole.guard.runtime

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.runtime.network.PublicRemoteDns
import okhttp3.Dns
import okhttp3.OkHttpClient

// URL admission and the actual connection must resolve through the same selected network.
internal fun publicUpdateDns(client: OkHttpClient, resolver: RemoteHostResolver?): Dns =
    PublicRemoteDns(resolver ?: client.dns::lookup)
