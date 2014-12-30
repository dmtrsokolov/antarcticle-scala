package security

import models.UserModels.User
import models.database.UserRecord
import repositories.UsersRepositoryComponent
import services.{MailServiceComponent, SessionProvider}
import utils.SecurityUtil
import validators.UserValidator

import scala.slick.jdbc.JdbcBackend
import scalaz.Scalaz._
import scalaz._

/**
 * Performs user authentication duty by login and password provided.
 * It also manages remember-me tokens for persistent user sessions.
 */
trait SecurityServiceComponent {
  val securityService: SecurityService

  trait SecurityService {
    /*
     * @return new remember me token for user if success
     */
    def signInUser(username: String, password: String): ValidationNel[String, (String, AuthenticatedUser)]
    def signUpUser(user: User, host: String): ValidationNel[String, UserRecord]
    def activateUser(uid: String): ValidationNel[String, UserRecord]
  }

}

trait SecurityServiceComponentImpl extends SecurityServiceComponent with MailServiceComponent {
  this: UsersRepositoryComponent with SessionProvider  =>

  val securityService = new SecurityServiceImpl
  val authenticationManager: AuthenticationManager
  val userValidator: UserValidator

  class SecurityServiceImpl extends SecurityService {

    def signInUser(username: String, password: String) = {

      def createOrUpdateUser(info: UserInfo): UserRecord = withSession {
        implicit s: JdbcBackend#Session =>
          val salt = some(SecurityUtil.generateSalt)
          val encodedPassword = SecurityUtil.encodePassword(password, salt)
          usersRepository.getByUsername(username.trim).cata(
            some = user => {
              val record = user.copy(password = encodedPassword, salt = salt,
                firstName = info.firstName, lastName = info.lastName, active = user.active)
              usersRepository.update(record)
              record
            },
            none = {
              val record = UserRecord(None, username.trim, encodedPassword, "NA", false, salt, info.firstName,
                info.lastName, active = true)
              record.copy(id = some(usersRepository.insert(record)))
            }
          )
      }

      for {
        user <- authenticationManager.authenticate(username.trim, password).cata(
          some = info => if (info.active) info.successNel else s"User ${info.username} is not active".failNel,
          none = "Invalid username or password".failNel
        )
        userRecord = createOrUpdateUser(user)
        authority = if (userRecord.admin) Authorities.Admin else Authorities.User
        authenticatedUser = AuthenticatedUser(userRecord.id.get, userRecord.username, authority)
        token = issueRememberMeTokenTo(authenticatedUser.userId)
      } yield (token, authenticatedUser)
    }



    private def issueRememberMeTokenTo(userId: Int) =
      withSession { implicit session: JdbcBackend#Session =>
        val token = SecurityUtil.generateRememberMeToken
        usersRepository.updateRememberToken(userId, token)
        token
      }

    def signUpUser(user: User, host: String): ValidationNel[String, UserRecord] = withSession {
      implicit s: JdbcBackend#Session =>

      def validateUniqueness(user: User) = {
        for {
          _ <- usersRepository.getByUsername(user.username).cata(
            existingUser => s"User with the username ${user.username} already exists".failNel,
            ().successNel
          )
          _ <- usersRepository.getByEmail(user.email).cata(
            existingUser => s"User with the email ${user.email} already exists".failNel,
            ().successNel
          )
        } yield ()
      }

      def sendActivationLink(user: UserRecord) = {
        val url = "http://" + host + "/activate/" + user.uid
        val message = s"""<p>Dear ${user.username}!</p>
          |<p>This mail is to confirm your registration at Antarticle.<br/>
          |Please follow the link below to activate your account <br/><a href='$url'>$url</a><br/>
          |Best regards,<br/><br/>
          |Antarticle.</p>""".stripMargin
        mailService.sendEmail(user.email, "Antarcticle account activation", message)
      }

      val result = for {
        _ <- userValidator.validate(user)
        _ <- validateUniqueness(user)
        salt = some(SecurityUtil.generateSalt)
        encodedPassword = SecurityUtil.encodePassword(user.password, salt)
        userRecord = UserRecord(None, user.username, encodedPassword, user.email, salt = salt)
        _ = sendActivationLink(userRecord)
        userId = usersRepository.insert(userRecord)
      } yield userRecord.copy(id = some(userId))
      result
    }


    def activateUser(uid: String): ValidationNel[String, UserRecord] = withSession {
      implicit s: JdbcBackend#Session =>
      usersRepository.getByUID(uid: String).cata(
        user => {
          if (!user.active) {
            val activatedUser = user.copy(rememberToken = Some(SecurityUtil.generateRememberMeToken), active = true)
            usersRepository.update(activatedUser)
            activatedUser.successNel
          } else {
            "User is already activated".failNel
          }
        },
        none = "There is no user with such uid".failNel
      )
    }
  }

}




