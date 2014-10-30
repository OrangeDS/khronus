package com.despegar.metrik.web.service.influx.parser

import com.sun.corba.se.spi.legacy.interceptor.UnknownType
import scala.concurrent.duration.FiniteDuration

trait Node {
  def toString: String
}

case class InfluxCriteria(projection: Projection,
    table: Table,
    filter: Option[Expression],
    groupBy: Option[GroupBy],
    limit: Option[Int]) extends Node {

  override def toString = Seq(Some(s"select [${projection.toString}] from [${table.toString}]"),
    filter.map(x ⇒ "where " + x.toString),
    groupBy.map(_.toString),
    limit.map(x ⇒ "limit " + x.toString)).flatten.mkString(" ")
}


sealed trait Projection extends Node
case class Field(name: String, alias: Option[String]) extends Projection {
  override def toString = s"$name as $alias"
}
case class AllField() extends Projection {
  override def toString = "*"
}


case class Table(name: String, alias: Option[String]) extends Node {
  override def toString = s"$name $alias"

}


object Functions {
  val Count = "count"
  val Min = "min"
  val Max = "max"
  val Avg = "avg"
  val Percentile50 = "p50"
  val Percentile80 = "p80"
  val Percentile90 = "p90"
  val Percentile95 = "p95"
  val Percentile99 = "p99"
  val Percentile999 = "p999"
}


trait Expression extends Node

trait ProjectionExpression extends Expression {
  def function: String
}
case class Count(name: String) extends ProjectionExpression {
  override def toString = s"count($name)"
  override def function = Functions.Count
}
case class Avg(name: String) extends ProjectionExpression {
  override def toString = s"avg($name)"
  override def function = Functions.Avg
}
case class Min(name: String) extends ProjectionExpression {
  override def toString = s"min($name)"
  override def function = Functions.Min
}
case class Max(name: String) extends ProjectionExpression {
  override def toString = s"max($name)"
  override def function = Functions.Max
}
case class Percentile50(name: String) extends ProjectionExpression {
  override def toString = s"p50($name)"
  override def function = Functions.Percentile50
}
case class Percentile80(name: String) extends ProjectionExpression {
  override def toString = s"p80($name)"
  override def function = Functions.Percentile80
}
case class Percentile90(name: String) extends ProjectionExpression {
  override def toString = s"p90($name)"
  override def function = Functions.Percentile90
}
case class Percentile95(name: String) extends ProjectionExpression {
  override def toString = s"p95($name)"
  override def function = Functions.Percentile95
}
case class Percentile99(name: String) extends ProjectionExpression {
  override def toString = s"p99($name)"
  override def function = Functions.Percentile99
}
case class Percentile999(name: String) extends ProjectionExpression {
  override def toString = s"p999($name)"
  override def function = Functions.Percentile999
}


trait BinaryOperation extends Expression {
  val leftExpression: Expression
  val rightExpression: Expression

  val operator: String

  override def toString = s"(${leftExpression.toString}) $operator (${rightExpression.toString})"
}

object Operators {
  val Or = "or"
  val And = "and"
  val Eq = "="
  val Neq = "<>"
  val Gte = ">="
  val Gt = ">"
  val Lte = "<="
  val Lt = "<"
}

case class Or(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Or
}
case class And(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.And
}
case class Eq(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Eq
}
case class Neq(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Neq
}
case class Ge(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Gte
}
case class Gt(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Gt
}
case class Le(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Lte
}
case class Lt(leftExpression: Expression, rightExpression: Expression) extends BinaryOperation {
  val operator = Operators.Lt
}

trait LiteralExpression extends Expression {
  def gatherFields = Seq.empty
}
case class IntLiteral(value: Long) extends LiteralExpression {
  override def toString = value.toString
}
case class Identifier(value: String) extends LiteralExpression {
  override def toString = value.toString
}
case class StringLiteral(value: String) extends LiteralExpression {
  override def toString = "\"" + value.toString + "\"" // TODO: escape...
}
case class NullLiteral() extends LiteralExpression {
  override def toString = "null"
}
case class DateLiteral(dateStr: String) extends LiteralExpression {
  override def toString = s"date [$dateStr]"
}


case class GroupBy(duration: FiniteDuration) extends Node {
  override def toString = s"group by $duration"
}

object TimeSuffixes {
  val Seconds = "s"
  val Minutes = "m"
  val Hours = "h"
  val Days = "d"
  val Weeks = "w"
}