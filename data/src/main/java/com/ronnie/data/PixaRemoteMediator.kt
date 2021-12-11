package com.ronnie.data

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.ronnie.data.api.PixaBayApi
import com.ronnie.data.db.PixaBayRoomDb
import com.ronnie.domain.Image
import com.ronnie.domain.RemoteKey
import retrofit2.HttpException
import java.io.IOException
import java.io.InvalidObjectException

@OptIn(ExperimentalPagingApi::class)
class PixaRemoteMediator(
    private val pixaBayApi: PixaBayApi,
    private val searchString: String,
    private val db: PixaBayRoomDb
) : RemoteMediator<Int, Image>() {

    override suspend fun load(loadType: LoadType, state: PagingState<Int, Image>): MediatorResult {
        val page: Int = when (loadType) {
                LoadType.REFRESH -> {
                    val remoteKeys = getRemoteKeyClosestToCurrentPosition(state)
                    remoteKeys?.nextPage?.minus(1) ?: 1
                }
                LoadType.PREPEND -> {
                    val remoteKeys = getRemoteKeyForFirstItem(state)

                    val prevKey = remoteKeys?.prevPage
                        ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                    prevKey
                }
                LoadType.APPEND -> {
                    val remoteKeys = getRemoteKeyForLastItem(state)
                    val nextKey = remoteKeys?.nextPage
                        ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                    nextKey
            }
        }

        try {
            val response = pixaBayApi.searchImages(searchString,state.config.initialLoadSize, page)
            val images = response.images

            images.map {
                it.searchTerm = searchString
            }

            val endOfPaginationReached = images.isEmpty()
            db.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    db.imageDao().clearAll()
                    db.remoteKeyDao().clearRemoteKeys()
                }
               val prevKey = if (page == 1) null else page - 1
               val  nextKey = if (endOfPaginationReached) null else page + 1

               val keys = images.map {
                    RemoteKey(prevPage = prevKey, nextPage = nextKey, imageId = it.id)
                }
                db.remoteKeyDao().insertAll(keys)
                db.imageDao().insertAll(images)
            }

            return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (exception: IOException) {
            return MediatorResult.Error(exception)
        } catch (exception: HttpException) {
            return MediatorResult.Error(exception)
        }

    }

    override suspend fun initialize(): InitializeAction {
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, Image>): RemoteKey? {
        return state.pages.lastOrNull { it.data.isNotEmpty() }?.data?.lastOrNull()
            ?.let { image ->
                db.remoteKeyDao().remoteKeysImageId(image.id)
            }
    }
    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, Image>): RemoteKey? {
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()
            ?.let { image ->
                db.remoteKeyDao().remoteKeysImageId(image.id)
            }
    }

    private suspend fun getRemoteKeyClosestToCurrentPosition(
        state: PagingState<Int, Image>
    ): RemoteKey? {
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.let { image ->
                    db.remoteKeyDao().remoteKeysImageId(image.id)
            }
        }
    }
}