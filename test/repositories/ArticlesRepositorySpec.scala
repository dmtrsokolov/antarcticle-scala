package repositories

import org.specs2.mutable._

import models.database._
import utils.DateImplicits._
import org.specs2.time.NoTimeConversions
import com.github.nscala_time.time.Imports._

class ArticlesRepositorySpec extends Specification with NoTimeConversions {
  object repository extends SlickArticlesRepositoryComponent with UsersComponent with ArticlesComponent with Profile {
    val profile = scala.slick.driver.H2Driver
  }


  //TODO: what the fuck is going on here!? can't acquire connection through DriverManager.
  // looks like classloader issue in sbt.
  // broken:  val db = scala.slick.session.Database.forURL("jdbc:h2:mem:test1", driver = "org.h2.Driver")
  val db = scala.slick.session.Database.forDriver(
    driver = Class.forName("org.h2.Driver").newInstance.asInstanceOf[java.sql.Driver],
    url = "jdbc:h2:mem:test1")

  import repository._
  import profile.simple._

  def populateDb(implicit session: Session) = {
    (Articles.ddl ++ Users.ddl).create

    val time = DateTime.now
    val users = List(UserRecord(None, "user1"),  UserRecord(None, "user2"))
    val articles = List(
      ArticleRecord(None, "New title 1", "<b>content</b>", time + 1.day, time, "description1", 1),
      ArticleRecord(None, "New title 2", "<i>html text</i>", time, time, "description2", 2),
      ArticleRecord(None, "New title 3", "<i>html text</i>", time + 2.days, time, "description3", 2),
      ArticleRecord(None, "New title 4", "<i>html text</i>", time + 4.days, time, "description4", 2)
    )

    Users.autoInc.insertAll(users : _*)
    val articlesIds = Articles.autoInc.insertAll(articles: _*)

    val articlesWithId = articles.zipWithIndex.map {
      case (article, idx) => article.copy(id = Some(articlesIds(idx)))
    }
    (users, articlesWithId)
  }

  def session[T](t: (Session) => T) = {
    db withSession { implicit session: Session =>
      t(session)
    }
  }


  "list portion" should {
    "return portion with size 2" in session { implicit session: Session =>
      populateDb

      val offset = 0
      val portionSize = 2

      val portion = articlesRepository.getList(offset, portionSize)

      portion must have size 2
    }

    "return portion with offset 1" in session { implicit session: Session =>
      val (_, articles) = populateDb
      val secondArticle = articles(1)

      val offset = 1
      val portionSize = 2

      val portion = articlesRepository.getList(offset, portionSize)

      val firstArticleInPortion = portion(0)._1
      firstArticleInPortion must_== secondArticle
    }

    "be sorted by creation time" in session { implicit session: Session =>
      val (_, articles) = populateDb

      val portion = articlesRepository.getList(0, 2)

      portion.map(_._1) must contain(exactly(articles(1), articles(0)).inOrder)
    }
    //TODO: test author correctness
  }


  "article by id" should {
    "return article with id 2" in session { implicit session: Session =>
      populateDb

      val article = articlesRepository.get(2)

      article must beSome
      article.flatMap(_._1.id) must beSome(2)
    }

    "return None when there are no article with id 2000" in session { implicit session: Session =>
      populateDb

      val article = articlesRepository.get(2000)

      article must beNone
    }
  }

  "inserting new article" should {
    "inserts new article record" in session { implicit session: Session =>
      val (_, articles) = populateDb
      val oldCount = articles.size

      val userId = 2
      val newArticle = ArticleRecord(None, "test article", "content", DateTime.now, DateTime.now, "descr", userId)

      articlesRepository.insert(newArticle)

      articlesCount must_== (oldCount + 1)
    }

    "assigns id to new article" in session { implicit session: Session =>
      populateDb

      val userId = 2
      val newArticle = ArticleRecord(None, "test article", "content", DateTime.now, DateTime.now, "descr", userId)

      val insertedArticle = articlesRepository.insert(newArticle)

      insertedArticle.id must beSome
    }
  }

  "updating article" should {
    "updates existing article" in session { implicit session: Session =>
      val (_, articles) = populateDb
      val articleToBeUpdated = articles(1)

      val newContent = "new content"
      val upd = ArticleToUpdate(articleToBeUpdated.title,
        newContent, articleToBeUpdated.createdAt, articleToBeUpdated.description)

      //TODO: split assertions
      articlesRepository.update(articleToBeUpdated.id.get, upd) must beTrue
      val actualContent = Query(Articles).filter(_.id === articleToBeUpdated.id).map(_.content).first
      actualContent must_== newContent
    }

    "returns false when updating not existing article" in session { implicit session: Session =>
      populateDb

      val upd = ArticleToUpdate("title", "content", DateTime.now, "desc")

      articlesRepository.update(2000, upd) must beFalse
    }
  }

  "removing article" should {
    "removes article" in session { implicit session: Session =>
      val (_, articles) = populateDb
      val oldCount = articles.size

      articlesRepository.remove(2) must beTrue
      articlesCount must_== (oldCount - 1)
    }

    "removes expected article" in session { implicit session: Session =>
      populateDb
      val articleId = 2

      articlesRepository.remove(articleId)

      Articles.filter(_.id === articleId).list must be empty
    }

    "returns false when article not exists" in session { implicit session: Session =>
      populateDb
      articlesRepository.remove(1000) must beFalse
    }
  }

  def articlesCount(implicit session: Session) = Query(Articles.length).first
}
