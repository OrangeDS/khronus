package com.despegar.metrik.web.service.influx.parser

import java.util.concurrent.TimeUnit

import com.despegar.metrik.util.Logging

import scala.concurrent.duration.FiniteDuration
import scala.util.parsing.combinator.lexical._
import scala.util.parsing.combinator.syntactical._
import com.despegar.metrik.model.Functions

class InfluxQueryParser extends StandardTokenParsers with Logging {

  class InfluxLexical extends StdLexical

  override val lexical = new InfluxLexical

  val functions = Functions.allValuesAsString

  lexical.reserved += ("select", "as", "from", "where", "or", "and", "group_by_time", "limit", "between", "null", "date", "time", "now",
    TimeSuffixes.Seconds, TimeSuffixes.Minutes, TimeSuffixes.Hours, TimeSuffixes.Days, TimeSuffixes.Weeks)

  lexical.reserved ++= functions

  lexical.delimiters += ("*", Operators.Lt, Operators.Eq, Operators.Neq, Operators.Lte, Operators.Gte, Operators.Gt, "(", ")", ",", ".", ";", "-")

  def parse(influxQuery: String): Option[InfluxCriteria] = {
    log.info(s"Parsing influx query [$influxQuery]")

    // TODO - Hack because of conflict: group by time & time as identifier
    val queryToParse = influxQuery.replace("group by time", "group_by_time")

    phrase(influxQueryParser)(new lexical.Scanner(queryToParse)) match {
      case Success(r, q) ⇒ Option(r)
      case x             ⇒ log.error(s"Error parsing query [$influxQuery]: $x"); None
    }
  }

  private def influxQueryParser: Parser[InfluxCriteria] =
    "select" ~> projectionParser ~
      tableParser ~ opt(filterParser) ~
      groupByParser ~ opt(limitParser) <~ opt(";") ^^ {
        case projection ~ table ~ filters ~ groupBy ~ limit ⇒ InfluxCriteria(projection, table, filters.getOrElse(Nil), groupBy, limit)
      }

  private def projectionParser: Parser[Projection] =
    "*" ^^ (_ ⇒ AllField()) |
      projectionExpressionParser ~ opt("as" ~> ident) ^^ {
        case x ~ alias ⇒ {
          x match {
            case id: Identifier             ⇒ Field(id.value, alias)
            case proj: ProjectionExpression ⇒ Field(proj.function, alias)
          }
        }
      }

  private def projectionExpressionParser: Parser[Expression] =
    ident ^^ (Identifier(_)) |
      knownFunctionParser

  private def knownFunctionParser: Parser[Expression] =
    Functions.Count.value ~> "(" ~> ident <~ ")" ^^ (Count(_)) |
      Functions.Min.value ~> "(" ~> ident <~ ")" ^^ (Min(_)) |
      Functions.Max.value ~> "(" ~> ident <~ ")" ^^ (Max(_)) |
      Functions.Avg.value ~> "(" ~> ident <~ ")" ^^ (Avg(_)) |
      Functions.Percentile50.value ~> "(" ~> ident <~ ")" ^^ (Percentile50(_)) |
      Functions.Percentile80.value ~> "(" ~> ident <~ ")" ^^ (Percentile80(_)) |
      Functions.Percentile90.value ~> "(" ~> ident <~ ")" ^^ (Percentile90(_)) |
      Functions.Percentile95.value ~> "(" ~> ident <~ ")" ^^ (Percentile95(_)) |
      Functions.Percentile99.value ~> "(" ~> ident <~ ")" ^^ (Percentile99(_)) |
      Functions.Percentile999.value ~> "(" ~> ident <~ ")" ^^ (Percentile999(_))

  private def tableParser: Parser[Table] =
    "from" ~> ident ~ opt("as") ~ opt(ident) ^^ {
      case ident ~ _ ~ alias ⇒ Table(ident, alias)
    }

  private def filterParser: Parser[List[Filter]] = "where" ~> filterExpression

  private def filterExpression: Parser[List[Filter]] = rep(
    stringComparatorExpression |
      timestampComparatorExpression |
      timeBetweenExpression |
      nowMinusIntervalExpression).map(x ⇒ x.flatten)

  def stringComparatorExpression: Parser[List[StringFilter]] = {
    ident ~ (Operators.Eq | Operators.Neq) ~ stringParser <~ opt(Operators.And) ^^ {
      case identifier ~ operator ~ strValue ⇒ List(StringFilter(identifier, operator, strValue))
    }
  }

  def timestampComparatorExpression: Parser[List[TimeFilter]] = {
    "time" ~ (Operators.Lt | Operators.Lte | Operators.Gt | Operators.Gte) ~ numericParser <~ opt(Operators.And) ^^ {
      case identifier ~ operator ~ longValue ⇒ List(TimeFilter(identifier, operator, longValue))
    }
  }

  def timeBetweenExpression: Parser[List[TimeFilter]] = {
    "time" ~ "between" ~ numericParser ~ "and" ~ numericParser <~ opt(Operators.And) ^^ {
      case identifier ~ _ ~ longValueA ~ _ ~ longValueB ⇒ List(TimeFilter(identifier, Operators.Gte, longValueA), TimeFilter(identifier, Operators.Lte, longValueB))
    }
  }

  def nowMinusIntervalExpression: Parser[List[TimeFilter]] = {
    "time" ~ (Operators.Lt | Operators.Lte | Operators.Gt | Operators.Gte) ~ "now" ~ "(" ~ ")" ~ opt("-") ~ opt(timeSuffixParser) <~ opt(Operators.And) ^^ {
      case identifier ~ operator ~ _ ~ _ ~ _ ~ _ ~ durationInMillis ⇒ {
        List(TimeFilter(identifier, operator, now - durationInMillis.map(_.length).getOrElse(0L)))
      }
    }
  }

  private def timeSuffixParser: Parser[FiniteDuration] = {
    numericLit ~ (TimeSuffixes.Seconds | TimeSuffixes.Minutes | TimeSuffixes.Hours | TimeSuffixes.Days | TimeSuffixes.Weeks) ^^ {
      case number ~ timeUnit ⇒ {
        timeUnit match {
          case TimeSuffixes.Seconds ⇒ new FiniteDuration(TimeUnit.SECONDS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Minutes ⇒ new FiniteDuration(TimeUnit.MINUTES.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Hours   ⇒ new FiniteDuration(TimeUnit.HOURS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Days    ⇒ new FiniteDuration(TimeUnit.DAYS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Weeks   ⇒ new FiniteDuration(TimeUnit.DAYS.toMillis(number.toLong) * 7, TimeUnit.MILLISECONDS)
        }
      }
    }
  }

  private def groupByParser: Parser[GroupBy] =
    "group_by_time" ~> "(" ~> timeWindowParser <~ ")" ^^ (GroupBy(_))

  private def timeWindowParser: Parser[FiniteDuration] =
    ((numberEqualTo(30) ~ TimeSuffixes.Seconds) | (numberEqualTo(1) ~ TimeSuffixes.Minutes) | (numberEqualTo(5) ~ TimeSuffixes.Minutes) |
      (numberEqualTo(10) ~ TimeSuffixes.Minutes) | (numberEqualTo(30) ~ TimeSuffixes.Minutes) | (numberEqualTo(1) ~ TimeSuffixes.Hours)) ^^ {
        case number ~ timeUnit ⇒ {
          timeUnit match {
            case TimeSuffixes.Seconds ⇒ new FiniteDuration(number.toLong, TimeUnit.SECONDS)
            case TimeSuffixes.Minutes ⇒ new FiniteDuration(number.toLong, TimeUnit.MINUTES)
            case TimeSuffixes.Hours   ⇒ new FiniteDuration(number.toLong, TimeUnit.HOURS)
          }
        }
      }

  private def limitParser: Parser[Int] = "limit" ~> numericLit ^^ (_.toInt)

  protected def now: Long = System.currentTimeMillis()

  private def numericParser: Parser[Long] = numericLit ^^ {
    case i ⇒ i.toLong
  }

  private def numberEqualTo(n: Int): Parser[Int] =
    elem(s"Expected number $n", _.toString == n.toString) ^^ (_.toString.toInt)

  private def stringParser: Parser[String] = stringLit ^^ {
    case s ⇒ s
  }

}
