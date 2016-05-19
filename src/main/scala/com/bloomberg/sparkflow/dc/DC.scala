package com.bloomberg.sparkflow.dc

import java.io.File

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType
import scala.language.implicitConversions

import scala.reflect.ClassTag
import com.bloomberg.sparkflow
import com.bloomberg.sparkflow._
import scala.reflect.runtime.universe.TypeTag
import scala.reflect._
import sparkflow.dc.Util._

/**
  * DistributedCollection, analogous to RDD
  */
abstract class DC[T: ClassTag](deps: Seq[Dependency[_]]) extends Dependency[T](deps) {

  private var rdd: RDD[T] = _
  private var checkpointed = false
  private var schema: Option[StructType] = None
  private var assigned = false

  protected def computeSparkResults(sc: SparkContext): (RDD[T], Option[StructType])


  def map[U: ClassTag](f: T => U): DC[U] = {
    new RDDTransformDC(this, (rdd: RDD[T]) => rdd.map(f), f)
  }

  def filter(f: T => Boolean): DC[T] = {
    new RDDTransformDC(this, (rdd: RDD[T]) => rdd.filter(f), f)
  }

  def flatMap[U: ClassTag](f: T => TraversableOnce[U]): DC[U] = {
    new RDDTransformDC(this, (rdd: RDD[T]) => rdd.flatMap(f), f)
  }

  def zipWithUniqueId(): DC[(T, Long)] = {
    new RDDTransformDC(this, (rdd: RDD[T]) => rdd.zipWithUniqueId, Seq("zipWithUniqueId"))
  }

  def mapToResult[U:ClassTag](f: RDD[T] => U): DR[U] ={
    new DRImpl[T,U](this, f)
  }

  def mapWith[U:ClassTag, V:ClassTag](dr: DR[U])(f: (T,U) => V) = {
    new ResultDepDC(this, dr, f)
  }

  def checkpoint(): this.type = {
    this.checkpointed = true
    this
  }

  def repartition(numPartitions: Int) = {
    new RDDTransformDC(this, (rdd: RDD[T]) => rdd.repartition(numPartitions), Seq("repartition", numPartitions.toString))
  }

  def getRDD(sc: SparkContext): RDD[T] = {

    synchronized {
      if (!assigned) {
        if (checkpointed) {
          loadCheckpoint[T](checkpointPath, sc, dataFrameBacked) match {
            case Some((resultRdd, resultSchema)) => assignSparkResults(resultRdd, resultSchema)
            case None =>
              val (resultRDD, resultSchema) = computeSparkResults(sc)
              resultRDD.persist(defaultPersistence)
              resultRDD.count()
              saveCheckpoint(checkpointPath, resultRDD, resultSchema, dataFrameBacked)
              loadCheckpoint[T](checkpointPath, sc, dataFrameBacked) match {
                case Some((rRDD, rSchema)) => assignSparkResults(rRDD, rSchema)
                case None => throw new RuntimeException(s"failed to persist to: $checkpointPath")
              }
          }
        } else {
          assignComputedSparkResults(sc)
        }
      }
    }
    rdd
  }

  def getSchema(sc: SparkContext): Option[StructType] = {
    if(!assigned) {
      getRDD(sc)
    }
    schema
  }

  private def checkpointPath = new File(checkpointDir, getSignature).toString

  private def assignSparkResults(resultRdd: RDD[T], resultSchema: Option[StructType]) = {
    synchronized {
      assert(!assigned)
      this.rdd = resultRdd
      this.schema = resultSchema
      assigned = true
    }
  }

  private def assignComputedSparkResults(sc: SparkContext) = {
    synchronized {
      assert(!assigned)
      val (resultRdd, resultSchema) = computeSparkResults(sc)
      assignSparkResults(resultRdd, resultSchema)
    }
  }

  private def dataFrameBacked = {
    this.ct.equals(classTag[Row])
  }

}

object DC {

  implicit def dcToPairDCFunctions[K, V](dc: DC[(K, V)])
    (implicit kt: ClassTag[K], vt: ClassTag[V], ord: Ordering[K] = null): PairDCFunctions[K, V] = {
    new PairDCFunctions(dc)
  }

  implicit def dcToDFFunctions(dc: DC[Row]): DataFrameDCFunctions = {
    new DataFrameDCFunctions(dc)
  }

  implicit def dcToProductDCFunctions[T <: Product : TypeTag](dc: DC[T]): ProductDCFunctions[T] = {
    new ProductDCFunctions[T](dc)
  }
}