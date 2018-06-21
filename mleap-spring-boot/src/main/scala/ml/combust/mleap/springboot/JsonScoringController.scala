package ml.combust.mleap.springboot

import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import ml.combust.mleap.executor._
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation._

import scala.compat.java8.FutureConverters._
import TypeConverters._
import com.google.protobuf.ByteString
import ml.combust.mleap.pb
import ml.combust.mleap.pb.TransformFrameResponse
import ml.combust.mleap.pb.TransformStatus.STATUS_ERROR
import ml.combust.mleap.runtime.serialization.{FrameReader, FrameWriter}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.json4s.jackson.JsonMethods
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}
import scalapb.json4s.{Parser, Printer}

@RestController
@RequestMapping
class JsonScoringController(@Autowired val actorSystem : ActorSystem,
                            @Autowired val mleapExecutor: MleapExecutor,
                            @Autowired val jsonPrinter: Printer,
                            @Autowired val jsonParser: Parser) {

  private val executor = actorSystem.dispatcher

  @PostMapping(path = Array("/models"),
              consumes = Array("application/json; charset=UTF-8"),
              produces = Array("application/json; charset=UTF-8"))
  def loadModel(@RequestBody request: String,
                @RequestHeader(value = "timeout", defaultValue = "60000") timeout: Int) : CompletionStage[String] =
    mleapExecutor
      .loadModel(jsonParser.fromJsonString[pb.LoadModelRequest](request))(timeout)
      .map(executorToPbModel)(executor)
      .map(model => JsonMethods.compact(jsonPrinter.toJson(model)))(executor).toJava

  @DeleteMapping(path = Array("/models/{model_name}"),
                consumes = Array("application/json; charset=UTF-8"),
                produces = Array("application/json; charset=UTF-8"))
  def unloadModel(@PathVariable("model_name") modelName: String,
                  @RequestHeader(value = "timeout", defaultValue = "60000") timeout: Int): CompletionStage[String] =
    mleapExecutor
      .unloadModel(UnloadModelRequest(modelName))(timeout)
      .map(executorToPbModel)(executor)
      .map(model => JsonMethods.compact(jsonPrinter.toJson(model)))(executor).toJava

  @GetMapping(path = Array("/models/{model_name}"),
              consumes = Array("application/json; charset=UTF-8"),
              produces = Array("application/json; charset=UTF-8"))
  def getModel(@PathVariable("model_name") modelName: String,
               @RequestHeader(value = "timeout", defaultValue = "60000") timeout: Int): CompletionStage[String] =
    mleapExecutor
      .getModel(GetModelRequest(modelName))(timeout)
      .map(executorToPbModel)(executor)
      .map(model => JsonMethods.compact(jsonPrinter.toJson(model)))(executor).toJava

  @GetMapping(path = Array("/models/{model_name}/meta"),
              consumes = Array("application/json; charset=UTF-8"),
              produces = Array("application/json; charset=UTF-8"))
  def getMeta(@PathVariable("model_name") modelName: String,
              @RequestHeader(value = "timeout", defaultValue = "60000") timeout: Int) : CompletionStage[String] =
    mleapExecutor
      .getBundleMeta(GetBundleMetaRequest(modelName))(timeout)
      .map(executorToPbBundleMeta)(executor)
      .map(meta => JsonMethods.compact(jsonPrinter.toJson(meta)))(executor).toJava

  @PostMapping(path = Array("/models/{model_name}/transform"),
              consumes = Array("application/json; charset=UTF-8"),
              produces = Array("application/json; charset=UTF-8"))
  def transform(@RequestBody body: String,
                @RequestHeader(value = "timeout", defaultValue = "60000") timeout: Int) : CompletionStage[String] = {
    val request = jsonParser.fromJsonString[pb.TransformFrameRequest](body)
    mleapExecutor.transform(TransformFrameRequest(request.modelName,
      FrameReader(request.format).fromBytes(request.frame.toByteArray).get, request.getOptions))(timeout)
      .mapAll {
        case Success(resp) => TransformFrameResponse(tag = request.tag,
              frame = ByteString.copyFrom(FrameWriter(resp.get, request.format).toBytes().get))
        case Failure(ex) => {
                            JsonScoringController.logger.error("Transform error due to ", ex)
                            TransformFrameResponse(tag = request.tag, status = STATUS_ERROR,
                              error = ExceptionUtils.getMessage(ex), backtrace = ExceptionUtils.getStackTrace(ex))
                            }
      }(executor)
      .map(resp => JsonMethods.compact(jsonPrinter.toJson(resp)))(executor).toJava
  }
}

object JsonScoringController {
  val logger = LoggerFactory.getLogger(classOf[JsonScoringController])
}