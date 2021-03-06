package controllers.filters

import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.Future

/**
 * All data modification requests (POST, PUT, DELETE) are submitted by JS client code.
 * This filter ensures that nobody can perform CSRF attack by making user submit
 * a form from external resource while Antarcticle session is still active
 */
object CsrfFilter extends Filter {
  def apply(next: (RequestHeader) => Future[Result])(rh: RequestHeader)  = {
    rh.method  match {
      case "POST" | "PUT" | "DELETE" =>
        rh.headers.get("X-Requested-With") match {
          case Some("XMLHttpRequest") => next(rh)
          case _ => Future.successful(Forbidden("This request was generated by unauthorized application or resource"))
        }
      case _ => next(rh)
    }
  }
}
