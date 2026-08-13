package ru.anisimov.keenwg.data.routes

import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.anisimov.keenwg.data.companion.ExactPinTrustManager
import ru.anisimov.keenwg.domain.model.RouterProfile

@Serializable data class ScenarioModules(val devices:Boolean=false,val services:Boolean=false,val domains:Boolean=false,val ip:Boolean=false)
@Serializable data class ScenarioConditions(
    @SerialName("device_ids") val deviceIds:List<String> = emptyList(), val services:List<String> = emptyList(),
    val domains:List<String> = emptyList(), val suffixes:List<String> = emptyList(), val geosites:List<String> = emptyList(), val cidrs:List<String> = emptyList(),
)
@Serializable data class ScenarioOutcome(val mode:String,@SerialName("group_id") val groupId:String?=null)
@Serializable data class ScenarioPreset(val id:String,val label:String,val optional:Boolean,val conditions:ScenarioConditions=ScenarioConditions(),val outcome:ScenarioOutcome=ScenarioOutcome("direct"))
@Serializable data class ScenarioCatalog(@SerialName("schema_version")val schemaVersion:Int,@SerialName("state_version")val stateVersion:ULong,val modules:ScenarioModules,val presets:List<ScenarioPreset>)
@Serializable data class ScenarioStep(val module:String,@SerialName("match_kind")val matchKind:String,val value:String,val outcome:ScenarioOutcome)
@Serializable data class ScenarioPlan(@SerialName("schema_version")val schemaVersion:Int,@SerialName("preset_id")val presetId:String,@SerialName("state_version")val stateVersion:ULong,val outcome:ScenarioOutcome,val steps:List<ScenarioStep>,@SerialName("skipped_modules")val skippedModules:List<String>)
@Serializable data class ScenarioReview(@SerialName("schema_version")val schemaVersion:Int,@SerialName("plan_id")val planId:String,val plan:ScenarioPlan)
@Serializable data class ScenarioApplyResult(val status:String,@SerialName("error")val errorCode:String?=null,@SerialName("plan_id")val planId:String?=null)
@Serializable data class RecoveryState(@SerialName("schema_version")val schemaVersion:Int,val pending:Boolean,@SerialName("plan_id")val planId:String?=null,val modules:List<String>)
@Serializable private data class ScenarioReviewRequest(@SerialName("schema_version")val schemaVersion:Int=1,@SerialName("state_version")val stateVersion:ULong)
@Serializable private data class ScenarioApplyRequest(@SerialName("schema_version")val schemaVersion:Int=1,@SerialName("reviewed_state_version")val stateVersion:ULong,@SerialName("reviewed_plan_id")val planId:String,@SerialName("idempotency_key")val key:String)
@Serializable private data class RecoveryRequest(@SerialName("schema_version")val schemaVersion:Int=1,val action:String="rollback",@SerialName("reviewed_plan_id")val planId:String)

interface ScenarioGateway{
    suspend fun catalog(profile:RouterProfile,token:String):ScenarioCatalog
    suspend fun review(profile:RouterProfile,token:String,presetId:String,stateVersion:ULong):ScenarioReview
    suspend fun apply(profile:RouterProfile,token:String,presetId:String,stateVersion:ULong,planId:String):ScenarioApplyResult
    suspend fun recovery(profile:RouterProfile,token:String):RecoveryState
    suspend fun rollback(profile:RouterProfile,token:String,planId:String):ScenarioApplyResult
}

internal val scenarioWireJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

class ScenarioClient(private val json:Json=scenarioWireJson,private val keyFactory:()->String={UUID.randomUUID().toString()}):ScenarioGateway{
    private val clients=ConcurrentHashMap<ClientKey,OkHttpClient>()
    override suspend fun catalog(profile:RouterProfile,token:String)=request<ScenarioCatalog>(profile,token,"/v1/scenarios","GET",null).also(::validate)
    override suspend fun review(profile:RouterProfile,token:String,presetId:String,stateVersion:ULong)=request<ScenarioReview>(profile,token,"/v1/scenarios/${safeID(presetId)}/review","POST",json.encodeToString(ScenarioReviewRequest(stateVersion=stateVersion))).also(::validate)
    override suspend fun apply(profile:RouterProfile,token:String,presetId:String,stateVersion:ULong,planId:String):ScenarioApplyResult{
        require(PLAN_ID.matches(planId));val result=request<ScenarioApplyResult>(profile,token,"/v1/scenarios/${safeID(presetId)}/apply","POST",json.encodeToString(ScenarioApplyRequest(stateVersion=stateVersion,planId=planId,key=keyFactory())));require(result.status in RESULTS);return result
    }
    override suspend fun recovery(profile:RouterProfile,token:String)=request<RecoveryState>(profile,token,"/v1/recovery","GET",null).also{require(it.schemaVersion==1&&(!it.pending||(it.planId!=null&&ID.matches(it.planId)))&&it.modules.size<=32)}
    override suspend fun rollback(profile:RouterProfile,token:String,planId:String):ScenarioApplyResult{require(ID.matches(planId));val result=request<ScenarioApplyResult>(profile,token,"/v1/recovery","POST",json.encodeToString(RecoveryRequest(planId=planId)));require(result.status in RESULTS);return result}
    private suspend inline fun<reified T>request(profile:RouterProfile,token:String,path:String,method:String,body:String?):T=withContext(Dispatchers.IO){
        require(token.isNotBlank()&&token.length<=512);val base=base(profile);val url=base.resolve(path)?:error("Некорректный адрес Companion");val builder=Request.Builder().url(url).header("Accept","application/json").header("Cache-Control","no-store").header("Authorization","Bearer $token");val call=if(method=="GET")builder.get().build()else builder.post(requireNotNull(body).toRequestBody(JSON)).build()
        val response=try{client(profile,base).newCall(call).execute()}catch(failure:IOException){throw IllegalStateException("Сценарии недоступны",failure)};response.use{val text=it.body?.charStream()?.use{reader->val out=StringBuilder();val buffer=CharArray(4096);while(true){val count=reader.read(buffer);if(count<0)break;out.append(buffer,0,count);require(out.length<=MAX_RESPONSE)};out.toString()}?:error("Пустой ответ Companion");if(!it.isSuccessful&&it.code !in setOf(409,503))error("Companion отклонил сценарий");json.decodeFromString<T>(text)}
    }
    private fun validate(value:ScenarioCatalog){require(value.schemaVersion==1&&value.stateVersion>0u&&value.presets.size<=128&&value.presets.all{ID.matches(it.id)&&it.label.isNotBlank()})}
    private fun validate(value:ScenarioReview){require(value.schemaVersion==1&&PLAN_ID.matches(value.planId)&&value.plan.schemaVersion==1&&value.plan.steps.size<=256)}
    private fun safeID(value:String)=value.also{require(ID.matches(it))}
    private fun base(profile:RouterProfile):HttpUrl{val url=profile.companionUrl.toHttpUrlOrNull()?:error("Companion не настроен");require(url.scheme=="https"&&url.encodedUsername.isEmpty()&&url.encodedPassword.isEmpty()&&url.query==null&&url.fragment==null&&(url.encodedPath=="/"||url.encodedPath.isEmpty()));return url}
    private fun client(profile:RouterProfile,base:HttpUrl)=clients.getOrPut(ClientKey(base.host,base.port,profile.certificatePin)){val trust=ExactPinTrustManager(profile.certificatePin);val context=SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),SecureRandom())};OkHttpClient.Builder().sslSocketFactory(context.socketFactory,trust).connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()}
    private data class ClientKey(val host:String,val port:Int,val pin:String)
    private companion object{val JSON="application/json; charset=utf-8".toMediaType();val ID=Regex("^[a-z0-9][a-z0-9._-]{0,63}$");val PLAN_ID=Regex("^[0-9a-f]{32}$");val RESULTS=setOf("committed","rejected","rolled_back","uncertain");const val MAX_RESPONSE=512*1024}
}
