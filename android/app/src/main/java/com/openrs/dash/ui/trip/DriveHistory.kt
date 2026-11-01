package com.openrs.dash.ui.trip

import com.openrs.dash.data.DriveDao
import com.openrs.dash.data.DriveEntity
import kotlinx.coroutines.flow.Flow

/**
 * TRIP's real drive history (#228): the list on screen is the observed Room query and nothing else. History edits
 * only write — the observation re-emits after each write. Each edit used to re-read the list itself and assign the
 * result, so a reload read before a newer write (a drive's end written after STOP, another edit) could land after
 * that write's emission and put the older list back until the next write to `drives`. Sample drives (preview, ids < 0)
 * never reach here. Blocking DAO calls — run the edits on IO.
 */
object DriveHistory {
    fun observe(dao: DriveDao, limit: Int): Flow<List<DriveEntity>> = dao.observeRecentDrives(limit)

    /** Delete a drive and the lap sessions it leaves behind (lap_sessions has no foreign key to drives). */
    fun delete(dao: DriveDao, id: Long) {
        dao.deleteDrive(id)
        dao.deleteOrphanLapSessions()
    }

    fun setTags(dao: DriveDao, id: Long, tags: String) = dao.updateDriveTags(id, tags)

    fun rename(dao: DriveDao, id: Long, name: String?) = dao.updateDriveName(id, name)
}
