package com.maxrave.domain.repository

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import kotlinx.coroutines.flow.Flow

interface OfflineDashboardRepository {
    fun getOfflineHomeShelves(): Flow<List<HomeItem>>
    suspend fun getOfflineMixes(): List<Content>
}
