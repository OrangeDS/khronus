package com.despegar.metrik.web.service.influx.parser

import scala.util.matching.Regex
import scala.util.parsing.combinator.lexical._
import scala.util.parsing.combinator.syntactical._

import scala.util.parsing.input.CharArrayReader.EofCh
import com.despegar.metrik.util.Logging
import scala.concurrent.duration.{FiniteDuration, Duration}
import java.util.concurrent.TimeUnit

class InfluxQueryParser extends StandardTokenParsers with Logging {

  class InfluxLexical extends StdLexical

  override val lexical = new InfluxLexical

  val functions = Seq(Functions.Count, Functions.Avg, Functions.Min, Functions.Max, Functions.Percentile50, Functions.Percentile80, Functions.Percentile90, Functions.Percentile95,
                      Functions.Percentile99, Functions.Percentile999)

  lexical.reserved += ("select", "as", "from", "where", "or", "and", "group_by_time", "limit", "between", "null", "date", TimeSuffixes.Seconds, TimeSuffixes.Minutes, TimeSuffixes.Hours, TimeSuffixes.Days, TimeSuffixes.Weeks)

  lexical.reserved ++= functions

  lexical.delimiters += ("*", Operators.Lt, Operators.Eq, Operators.Neq, Operators.Lte, Operators.Gte, Operators.Gt, "(", ")", ",", ".", ";")


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
      opt(groupByParser) ~ opt(limitParser) <~ opt(";") ^^ {
        case projection ~ table ~ filters ~ groupBy ~ limit ⇒ InfluxCriteria(projection, table, filters, groupBy, limit)
      }


  private def projectionParser: Parser[Projection] =
    "*" ^^ (_ ⇒ AllField()) |
      projectionExpressionParser ~ opt("as" ~> ident) ^^ {
        case x ~ alias ⇒ {
          x match {
            case id: Identifier       ⇒ Field(id.value, alias)
            case proj: ProjectionExpression ⇒ Field(proj.function, alias)
          }
        }
      }

  private def projectionExpressionParser: Parser[Expression] =
    ident ^^ (Identifier(_))|
      knownFunctionParser

  private def knownFunctionParser: Parser[Expression] =
    Functions.Count ~> "(" ~> ident <~ ")" ^^ (Count(_)) |
      Functions.Min ~> "(" ~> ident <~ ")" ^^ (Min(_)) |
      Functions.Max ~> "(" ~> ident <~ ")" ^^ (Max(_)) |
      Functions.Avg ~> "(" ~> ident <~ ")" ^^ (Avg(_)) |
      Functions.Percentile50 ~> "(" ~> ident <~ ")" ^^ (Percentile50(_)) |
      Functions.Percentile80 ~> "(" ~> ident <~ ")" ^^ (Percentile80(_)) |
      Functions.Percentile90 ~> "(" ~> ident <~ ")" ^^ (Percentile90(_)) |
      Functions.Percentile95 ~> "(" ~> ident <~ ")" ^^ (Percentile95(_)) |
      Functions.Percentile99 ~> "(" ~> ident <~ ")" ^^ (Percentile99(_)) |
      Functions.Percentile999 ~> "(" ~> ident <~ ")" ^^ (Percentile999(_))

  private def tableParser: Parser[Table] =
    "from" ~> ident ~ opt("as") ~ opt(ident) ^^ {
      case ident ~ _ ~ alias ⇒ Table(ident, alias)
    }


  private def filterParser: Parser[Expression] = "where" ~> filterExpression

  private def filterExpression: Parser[Expression] = orExpressionParser

  private def orExpressionParser: Parser[Expression] =
    andExpressionParser * (Operators.Or ^^^ { (left: Expression, right: Expression) ⇒ Or(left, right) })

  private def andExpressionParser: Parser[Expression] =
    comparatorExpression * (Operators.And ^^^ { (left: Expression, right: Expression) ⇒ And(left, right) })

  // TODO: this function is nasty- clean it up!
  private def comparatorExpression: Parser[Expression] =
    primaryExpressionParser ~ rep(
      (Operators.Eq | Operators.Neq | Operators.Lt | Operators.Lte | Operators.Gt | Operators.Gte) ~ primaryExpressionParser ^^ {
        case operator ~ rhs ⇒ (operator, rhs)
      } |
      "between" ~ primaryExpressionParser ~ "and" ~ primaryExpressionParser ^^ {
        case operator ~ a ~ _ ~ b ⇒ (operator, a, b)
      }) ^^ {
        case lhs ~ elems ⇒
          elems.foldLeft(lhs) {
            case (acc, ((Operators.Eq, rhs: Expression)))           ⇒ Eq(acc, rhs)
            case (acc, ((Operators.Neq, rhs: Expression)))          ⇒ Neq(acc, rhs)
            case (acc, ((Operators.Lt, rhs: Expression)))           ⇒ Lt(acc, rhs)
            case (acc, ((Operators.Lte, rhs: Expression)))          ⇒ Le(acc, rhs)
            case (acc, ((Operators.Gt, rhs: Expression)))           ⇒ Gt(acc, rhs)
            case (acc, ((Operators.Gte, rhs: Expression)))          ⇒ Ge(acc, rhs)
            case (acc, (("between", l: Expression, r: Expression))) ⇒ And(Ge(acc, l), Le(acc, r))
          }
      }

  private def primaryExpressionParser: Parser[Expression] =
    literalParser |
      knownFunctionParser |
      ident ^^ (Identifier(_))

  private def literalParser: Parser[Expression] =
    numericLit ^^ { case i ⇒ IntLiteral(i.toInt) } |
      stringLit ^^ { case s ⇒ StringLiteral(s) } |
      "null" ^^ (_ ⇒ NullLiteral()) |
      "date" ~> stringLit ^^ (DateLiteral(_))


  private def groupByParser: Parser[GroupBy] =
    "group_by_time" ~> "(" ~> timeSuffixParser <~ ")" ^^ (GroupBy(_))

  /*
  def groupByTimeParser: Parser[String] = {
    elem("group by time parser", x => "group by time".equalsIgnoreCase(x.toString)) ^^ (_.chars)
  }
  */


  private def timeSuffixParser: Parser[FiniteDuration] = {
    numericLit ~ (TimeSuffixes.Seconds | TimeSuffixes.Minutes | TimeSuffixes.Hours | TimeSuffixes.Days | TimeSuffixes.Weeks) ^^ {
      case number ~ timeUnit ⇒ {
        timeUnit match {
          case TimeSuffixes.Seconds => new FiniteDuration(TimeUnit.SECONDS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Minutes => new FiniteDuration(TimeUnit.MINUTES.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Hours => new FiniteDuration(TimeUnit.HOURS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Days => new FiniteDuration(TimeUnit.DAYS.toMillis(number.toLong), TimeUnit.MILLISECONDS)
          case TimeSuffixes.Weeks => new FiniteDuration(TimeUnit.DAYS.toMillis(number.toLong) * 7, TimeUnit.MILLISECONDS)
        }
      }
    }
  }


  private def limitParser: Parser[Int] = "limit" ~> numericLit ^^ (_.toInt)
}


