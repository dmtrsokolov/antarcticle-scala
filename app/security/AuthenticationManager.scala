package security

import models.database.UserRecord
import play.api.Logger
import play.api.Play.current
import play.api.libs.ws.{WS, WSResponse}
import repositories.UsersRepositoryComponent
import security.UserInfoImplicitConversions._
import services.SessionProvider
import utils.SecurityUtil

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.slick.jdbc.JdbcBackend
import scalaz.Scalaz._
import scalaz._

private[security] trait AuthenticationManager {
  def authenticate(username: String, password: String): Future[Option[UserInfo]]
  def activate(uuid: String): Future[ValidationNel[String, Unit]]
  def register(user: UserInfo): Future[ValidationNel[String, String]]
}

class FakeAuthenticationManager extends AuthenticationManager {

  val notSupported: ValidationNel[String, Nothing] = "Not supported by fake AuthenticationManager".failureNel
  
  override def authenticate(username: String, password: String) = Future.successful {
    (username, password) match {
      case ("admin", "admin") => some {
        UserInfo("admin", password, "email@email.com", "firstName".some, "lastName".some, true)
      }
      case _ => none[UserInfo]
    }
  }
  
  override def register(user: UserInfo) = Future.successful(notSupported)

  override def activate(uuid: String) = Future.successful(notSupported)
}

class PoulpeAuthenticationManager(poulpeUrl: String) extends AuthenticationManager {

  override def authenticate(username: String, password: String) = {
    for {
      response <- WS.url(s"$poulpeUrl/rest/authenticate")
        .withQueryString(("username", username), ("passwordHash", SecurityUtil.md5(password))).get()
      xmlResponseBody = response.xml
    } yield {
      for {
        status <- (xmlResponseBody \\ "status").headOption.map(_.text) if status == "success"
        enabled <- (xmlResponseBody \\ "enabled").headOption.map(_.text) if enabled == "true"
        returnedUsername <- (xmlResponseBody \\ "username").headOption.map(_.text)
        email <- (xmlResponseBody \\ "email").headOption.map(_.text)
        firstName = (xmlResponseBody \\ "firstName").headOption.map(_.text)
        lastName = (xmlResponseBody \\ "lastName").headOption.map(_.text)
        password = (xmlResponseBody \\ "password").headOption.map(_.text)
      } yield {
        UserInfo(returnedUsername, "todo", email, firstName, lastName, true)
      }
    }
  }

  override def register(user: UserInfo) = {

    def sendRequest(): Future[WSResponse] = {
      val data =
        s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <user xmlns="http://www.jtalks.org/namespaces/1.0">
          <username>${user.username}</username>
          <passwordHash>${user.password}</passwordHash>
          <email>${user.email}</email>
        </user>"""
      WS.url(s"$poulpeUrl/rest/private/user").post(data)
    }

    for {
      response <- sendRequest()
      errors = if (response.status == 200) List.empty[String] else getErrors(response, user)
    } yield {
      errors match {
        case head :: tail => Failure(NonEmptyList(head, tail : _*))
        case Nil => response.body.trim().successNel
      }
    }
  }
  override def activate(uuid: String): Future[ValidationNel[String, Unit]] = {
    for {
      response <- WS.url(s"$poulpeUrl/rest/activate").withQueryString(("uuid", uuid)).get()
      errors = if (response.status == 200) List.empty[String] else getErrors(response)
    } yield {
      errors match {
        case head :: tail => Failure(NonEmptyList(head, tail : _*))
        case Nil => ().successNel
      }
    }
  }
  private def translate(key : String, user: UserInfo) :String = {
    key match {
      case "user.username.already_exists" => s"User with the username ${user.username} already exists"
      case "user.email.already_exists" => s"User with the email ${user.email} already exists"
      case _ => "Some unexpected error occurred, please contact administrator or try later"
    }
  }

  private def getErrors(response: WSResponse, user: UserInfo): List[String] = {
    for {
      key <- getErrors(response)
      error = translate(key, user)
    } yield error
  }

  private def getErrors(response: WSResponse): List[String] = {
    (response.xml \ "error").map(x => (x \ "@code").text).toList
  }
}

class LocalDatabaseAuthenticationManager(repo: UsersRepositoryComponent, provider: SessionProvider)
  extends AuthenticationManager {

  override def authenticate(username: String, password: String) = provider.withSession {
    implicit s: JdbcBackend#Session =>
      def isValidPassword(user: UserRecord) = {
        !password.isEmpty && user.password === SecurityUtil.encodePassword(password, user.salt)
      }
      Future {
        repo.usersRepository.getByUsername(username) match {
          case user: Some[UserRecord] => if (isValidPassword(user.get)) Some(user.get) else None
          case _ => None
        }
      }
  }

  override def register(user: UserInfo) = provider.withSession {
    implicit s: JdbcBackend#Session =>

    def checkUsernameUnique = repo.usersRepository.getByUsername(user.username).cata(
      existingUser => s"User with the username ${user.username} already exists".failureNel,
      ().successNel
    )
    def checkEmailUnique = repo.usersRepository.getByEmail(user.email).cata(
      existingUser => s"User with the email ${user.email} already exists".failureNel,
      ().successNel
    )

    Future.successful {(checkUsernameUnique |@| checkEmailUnique) {
      case _ => SecurityUtil.generateUid
    }}
  }

  override def activate(uuid: String): Future[ValidationNel[String, Unit]] = provider.withSession {
    implicit s: JdbcBackend#Session =>
    Future {
      repo.usersRepository.getByUID(uuid).cata(
        user => if (!user.active) ().successNel else "User is already activated".failureNel,
        none = "There is no user with such uid".failureNel
      )
    }
  }
}

class CompositeAuthenticationManager(poulpeAuthManager: Option[AuthenticationManager],
                                     localAuthManager: AuthenticationManager)
  extends AuthenticationManager {
  override def authenticate(username: String, password: String) = {
    val p = Promise[Option[UserInfo]]()
    poulpeAuthManager.cata(
      none = localAuthManager.authenticate(username, password),
      some = manager => manager.authenticate(username, password)).onComplete {
      case s@scala.util.Success(x) => p.tryComplete(s)
      case scala.util.Failure(e) =>
        Logger.error("Error while asking Poulpe to authenticate user", e)
        p.completeWith(localAuthManager.authenticate(username, password))
    }
    p.future
  }

  override def register(user: UserInfo) = {
    poulpeAuthManager.cata(
      some = manager => {
        manager.register(user)
      },
      none = localAuthManager.register(user)
    )
  }

  override def activate(uuid: String) = {
    poulpeAuthManager.cata(
      some = manager => {
        manager.activate(uuid)
      },
      none = localAuthManager.activate(uuid)
    )
  }
}


