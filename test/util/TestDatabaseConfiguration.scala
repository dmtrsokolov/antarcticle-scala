package util

import models.database.Profile
import models.database.Schema
import services.SlickSessionProvider
import scala.slick.driver.{H2Driver, JdbcProfile}
import scala.slick.jdbc.JdbcBackend
import scala.slick.jdbc.JdbcBackend.Database
import migrations.{MigrationTool, MigrationsContainer}

/**
 * Configures H2 database for tests. Database scope is single session.
 **/
trait TestDatabaseConfiguration extends Profile with SlickSessionProvider {
  def driverInstance = Class.forName("org.h2.Driver").newInstance.asInstanceOf[java.sql.Driver]

  override val profile: JdbcProfile = H2Driver
  override val db: JdbcBackend#Database = Database.forDriver(driver = driverInstance, url = "jdbc:h2:mem:test1;DATABASE_TO_UPPER=false")
}

/**
 * Provides method withTestDb that executes migrations, populates database with fixtures and runs test method in one session.
 * Can be customized by overriding fixtures method.
 **/
trait TestDatabaseConfigurationWithFixtures extends TestDatabaseConfiguration with MigrationTool {
  this: Schema =>

  import com.github.nscala_time.time.Imports._
  import utils.Implicits._
  import models.database._
  import profile.simple._

  class EmptyMigrationsConainer extends MigrationsContainer

  override val migrationsContainer = new EmptyMigrationsConainer

  def withTestDb[T](f: JdbcBackend#Session => T): T = withSession { implicit session =>
    migrate
    fixtures
    f(session)
  }

  // you can override it in subclasses for more specific fixtures
  def fixtures(implicit session: JdbcBackend#Session): Unit = {
    val time = DateTime.now

    tags.map(_.name) ++= Seq("tag1", "tag2", "tag3")

    users ++= Seq(
      UserRecord(None, "user1", "password1"),
      UserRecord(None, "user2", "password2")
    )

    articles.map(a => (a.title, a.content, a.createdAt, a.updatedAt, a.description, a.authorId)) ++= Seq(
        ("New title 1", "<b>content</b>", time + 1.day, time, "description1", 1),
        ("New title 2", "<i>html text</i>", time, time, "description2", 2),
        ("New title 3", "<i>html text</i>", time + 2.days, time, "description3", 2),
        ("New title 4", "<i>html text</i>", time + 4.days, time, "description4", 2)
      )

    articlesTags ++= Seq(
       (1,1), (1,2), (2,1), (2,3)
    )

    comments ++= Seq(
      CommentRecord(None, 1, 1, "<b>content</b>", time + 1.hour, None, Some(false)),
      CommentRecord(None, 2, 1, "content", time + 1.day, None, Some(false)),
      CommentRecord(None, 2, 1, "dsf sdf s$#$ 4#%#$", time, None, Some(false)),
      CommentRecord(None, 1, 2, "42342", time + 1.week, None, Some(false)),
      CommentRecord(None, 2, 2, "", time + 1.second, None, Some(false)),
      CommentRecord(None, 2, 3, "dsfasdfsdaf", time, None, Some(false)),
      CommentRecord(None, 1, 4, "<b>content2</b>", time - 10.days, None, Some(false)),
      CommentRecord(None, 1, 1, "<b>content42342</b>", time + 5.minutes, None, Some(false))
    )
  }
}
