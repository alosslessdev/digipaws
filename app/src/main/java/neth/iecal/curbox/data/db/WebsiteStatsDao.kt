package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WebsiteStatsDao {

    @Query("SELECT * FROM website_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): List<WebsiteStatsEntity>
    
    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName AND urlIdentifier = :urlIdentifier")
    suspend fun getStat(date: String, packageName: String, urlIdentifier: String): WebsiteStatsEntity?

    @Query("SELECT * FROM website_stats WHERE date = :date AND packageName = :packageName")
    suspend fun getStatsForPackage(date: String, packageName: String): List<WebsiteStatsEntity>

    @Upsert
    suspend fun upsert(entity: WebsiteStatsEntity)
}
