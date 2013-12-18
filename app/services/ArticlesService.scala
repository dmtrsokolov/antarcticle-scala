package services

import org.joda.time.DateTime
import java.sql.Timestamp
import repositories.ArticlesRepositoryComponent
import models.database.{ArticleToUpdate, UserRecord, ArticleRecord}
import models.ArticleModels.{Article, ArticleDetailsModel, ArticleListModel}
import models.UserModels.UserModel
import utils.DateImplicits._
import scala.slick.session.Session

trait ArticlesServiceComponent {
  val articlesService: ArticlesService

  trait ArticlesService {
    def getPage(page: Int): List[ArticleListModel]
    def createArticle(article: Article): ArticleDetailsModel
    def get(id: Int): Option[ArticleDetailsModel]
    def updateArticle(article: Article)
    def removeArticle(id: Int)
  }
}

trait SessionProvider {
  def withSession[T](f: Session => T): T
  def withTransaction[T](f: Session => T): T
}


trait ArticlesServiceComponentImpl extends ArticlesServiceComponent {
  this: ArticlesRepositoryComponent with SessionProvider =>

  val articlesService = new ArticlesServiceImpl

  class ArticlesServiceImpl extends ArticlesService {
    def createArticle(article: Article) = withTransaction { implicit s: Session =>
      val currentTime = DateTime.now
      val currentUserId = 1 //TODO
      val newRecord = ArticleRecord(None, article.title, article.content, currentTime, currentTime, article.description, currentUserId)
      val inserted = articlesRepository.insert(newRecord)
      toDetailsModel(inserted, UserRecord(Some(1), ""))// TODO: real user
    }

    def updateArticle(article: Article) = withTransaction { implicit s: Session =>
      val modificationTime = DateTime.now
      //TODO: handle id
      articlesRepository.update(article.id.get, toRecord(article, modificationTime))
    }

    def removeArticle(id: Int) =  withTransaction { implicit s: Session =>
      articlesRepository.remove(id)
    }

    def get(id: Int) = withSession { implicit s: Session =>
      articlesRepository.get(id).map((toDetailsModel _).tupled)
    }

    def getPage(page: Int) = withSession { implicit s: Session =>
      val pageSize = 3 //TODO: move to some settings file
      val offset = pageSize * page
      articlesRepository.getList(offset, pageSize).map((toListModel _).tupled)
    }

    private def toRecord(article: Article, updatedAt: Timestamp) = {
      ArticleToUpdate(article.title, article.content, updatedAt, article.description)
    }

    private def toDetailsModel(articleRecord: ArticleRecord, authorRecord: UserRecord) = {
      ArticleDetailsModel(articleRecord.id.get, articleRecord.title,
        articleRecord.content, articleRecord.createdAt,
        UserModel(authorRecord.id.get, authorRecord.username))
    }

    private def toListModel(articleRecord: ArticleRecord, authorRecord: UserRecord) = {
      ArticleListModel(articleRecord.id.get, articleRecord.title,
        articleRecord.description, articleRecord.createdAt,
        UserModel(authorRecord.id.get, authorRecord.username))
    }
  }
}
