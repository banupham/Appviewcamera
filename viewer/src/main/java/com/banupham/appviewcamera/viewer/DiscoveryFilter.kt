package com.banupham.appviewcamera.viewer

import com.banupham.appviewcamera.viewer.api.CameraSummary
import com.banupham.appviewcamera.viewer.api.DiscoveryCandidate

internal fun availableDiscoveryCandidates(
    candidates: List<DiscoveryCandidate>,
    cameras: List<CameraSummary>
): List<DiscoveryCandidate> {
    val configuredEndpoints = cameras.map { it.host.trim().lowercase() to it.port }.toSet()
    return candidates
        .filterNot { it.port == 80 || it.port == 443 }
        .filterNot { (it.host.trim().lowercase() to it.port) in configuredEndpoints }
        .distinctBy { it.host.trim().lowercase() to it.port }
}
