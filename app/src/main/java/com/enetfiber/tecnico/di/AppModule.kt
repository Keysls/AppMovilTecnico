package com.enetfiber.tecnico.di

import android.content.Context
import androidx.room.Room
import com.enetfiber.tecnico.BuildConfig
import com.enetfiber.tecnico.data.local.*
import com.enetfiber.tecnico.data.remote.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "enetfiber.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13
            )
            .apply {
                if (BuildConfig.DEBUG) fallbackToDestructiveMigration()
            }
            .build()


    @Provides fun provideOrdenDao(db: AppDatabase)  = db.ordenDao()
    @Provides fun provideConfigDao(db: AppDatabase) = db.configOfflineDao()
    @Provides fun provideFotoDao(db: AppDatabase)   = db.fotoPendienteDao()

    @Provides fun provideCompletarDao(db: AppDatabase) = db.completarPendienteDao()

    @Provides fun provideAceptarDao(db: AppDatabase) = db.aceptarPendienteDao()
    @Provides fun provideIniciarDao(db: AppDatabase) = db.iniciarPendienteDao()
    // C2 FIX: cache del token en memoria. El interceptor ya no hace runBlocking
    // en cada request — solo lee un AtomicReference. El TokenProvider observa
    // el DataStore una vez y mantiene el valor actualizado.
    @Provides
    @Singleton
    fun provideTokenProvider(session: SessionDataStore): TokenProvider =
        TokenProvider(session)

    @Provides
    @Singleton
    fun provideOkHttp(
        tokenProvider: TokenProvider,
        @ApplicationContext ctx: Context
    ): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val token = tokenProvider.current()           // ← lectura instantánea, sin bloqueo
            val request = if (!token.isNullOrEmpty())
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            else chain.request()
            chain.proceed(request)
        }

        // C3 FIX: detecta 401 (token vencido) y dispara el cierre de sesión.
        val unauthorizedInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401 &&
                !chain.request().url.encodedPath.contains("auth/login")
            ) {
                SessionEvents.notifyUnauthorized()
            }
            response
        }


        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(unauthorizedInterceptor)   // ← NUEVO
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

        // C1 FIX: logging de BODY solo en debug. En release, NADA de logs
        // (los headers Authorization y el body del login exponen token y password).
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttp: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)


    @Provides fun provideInventarioDao(db: AppDatabase)       = db.inventarioDao()
    @Provides fun provideConsumoPendienteDao(db: AppDatabase) = db.consumoPendienteDao()
    @Provides fun provideCatalogoProductoDao(db: AppDatabase)  = db.catalogoProductoDao()
    @Provides fun provideRetiroPendienteDao(db: AppDatabase) = db.retiroPendienteDao()
}