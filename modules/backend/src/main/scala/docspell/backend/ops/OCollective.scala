package docspell.backend.ops

import cats.effect.{Effect, Resource}
import cats.implicits._
import fs2.Stream

import docspell.backend.PasswordCrypt
import docspell.backend.ops.OCollective._
import docspell.common._
import docspell.store.queries.QCollective
import docspell.store.queue.JobQueue
import docspell.store.records._
import docspell.store.usertask.UserTask
import docspell.store.usertask.UserTaskStore
import docspell.store.{AddResult, Store}

import com.github.eikek.calev._

trait OCollective[F[_]] {

  def find(name: Ident): F[Option[RCollective]]

  def updateSettings(collective: Ident, settings: OCollective.Settings): F[AddResult]

  def findSettings(collective: Ident): F[Option[OCollective.Settings]]

  def listUser(collective: Ident): F[Vector[RUser]]

  def add(s: RUser): F[AddResult]

  def update(s: RUser): F[AddResult]

  def deleteUser(login: Ident, collective: Ident): F[AddResult]

  def insights(collective: Ident): F[InsightData]

  def tagCloud(collective: Ident): F[List[TagCount]]

  def changePassword(
      accountId: AccountId,
      current: Password,
      newPass: Password
  ): F[PassChangeResult]

  def getContacts(
      collective: Ident,
      query: Option[String],
      kind: Option[ContactKind]
  ): Stream[F, RContact]

  def findEnabledSource(sourceId: Ident): F[Option[RSource]]

  def startLearnClassifier(collective: Ident): F[Unit]
}

object OCollective {

  type TagCount = QCollective.TagCount
  val TagCount = QCollective.TagCount

  type InsightData = QCollective.InsightData
  val insightData = QCollective.InsightData

  type Settings = RCollective.Settings
  val Settings = RCollective.Settings
  type Classifier = RClassifierSetting.Classifier
  val Classifier = RClassifierSetting.Classifier

  sealed trait PassChangeResult
  object PassChangeResult {
    case object UserNotFound     extends PassChangeResult
    case object PasswordMismatch extends PassChangeResult
    case object UpdateFailed     extends PassChangeResult
    case object Success          extends PassChangeResult

    def userNotFound: PassChangeResult     = UserNotFound
    def passwordMismatch: PassChangeResult = PasswordMismatch
    def success: PassChangeResult          = Success
    def updateFailed: PassChangeResult     = UpdateFailed
  }

  case class RegisterData(
      collName: Ident,
      login: Ident,
      password: Password,
      invite: Option[Ident]
  )

  sealed trait RegisterResult {
    def toEither: Either[Throwable, Unit]
  }
  object RegisterResult {
    case object Success extends RegisterResult {
      val toEither = Right(())
    }
    case class CollectiveExists(id: Ident) extends RegisterResult {
      val toEither = Left(new Exception())
    }
    case class Error(ex: Throwable) extends RegisterResult {
      val toEither = Left(ex)
    }
  }

  def apply[F[_]: Effect](
      store: Store[F],
      uts: UserTaskStore[F],
      queue: JobQueue[F],
      joex: OJoex[F]
  ): Resource[F, OCollective[F]] =
    Resource.pure[F, OCollective[F]](new OCollective[F] {
      def find(name: Ident): F[Option[RCollective]] =
        store.transact(RCollective.findById(name))

      def updateSettings(collective: Ident, sett: Settings): F[AddResult] =
        store
          .transact(RCollective.updateSettings(collective, sett))
          .attempt
          .map(AddResult.fromUpdate)
          .flatMap(res => updateLearnClassifierTask(collective, sett) *> res.pure[F])

      def updateLearnClassifierTask(coll: Ident, sett: Settings) =
        for {
          id <- Ident.randomId[F]
          on    = sett.classifier.map(_.enabled).getOrElse(false)
          timer = sett.classifier.map(_.schedule).getOrElse(CalEvent.unsafe(""))
          ut = UserTask(
            id,
            LearnClassifierArgs.taskName,
            on,
            timer,
            LearnClassifierArgs(coll)
          )
          _ <- uts.updateOneTask(AccountId(coll, LearnClassifierArgs.taskName), ut)
          _ <- joex.notifyAllNodes
        } yield ()

      def startLearnClassifier(collective: Ident): F[Unit] =
        for {
          id <- Ident.randomId[F]
          ut <- UserTask(
            id,
            LearnClassifierArgs.taskName,
            true,
            CalEvent(WeekdayComponent.All, DateEvent.All, TimeEvent.All),
            LearnClassifierArgs(collective)
          ).encode.toPeriodicTask(AccountId(collective, LearnClassifierArgs.taskName))
          job <- ut.toJob
          _   <- queue.insert(job)
          _   <- joex.notifyAllNodes
        } yield ()

      def findSettings(collective: Ident): F[Option[OCollective.Settings]] =
        store.transact(RCollective.getSettings(collective))

      def listUser(collective: Ident): F[Vector[RUser]] =
        store.transact(RUser.findAll(collective, _.login))

      def add(s: RUser): F[AddResult] =
        store.add(
          RUser.insert(s.copy(password = PasswordCrypt.crypt(s.password))),
          RUser.exists(s.login)
        )

      def update(s: RUser): F[AddResult] =
        store.add(RUser.update(s), RUser.exists(s.login))

      def deleteUser(login: Ident, collective: Ident): F[AddResult] =
        store.transact(RUser.delete(login, collective)).attempt.map(AddResult.fromUpdate)

      def insights(collective: Ident): F[InsightData] =
        store.transact(QCollective.getInsights(collective))

      def tagCloud(collective: Ident): F[List[TagCount]] =
        store.transact(QCollective.tagCloud(collective))

      def changePassword(
          accountId: AccountId,
          current: Password,
          newPass: Password
      ): F[PassChangeResult] = {
        val q = for {
          optUser <- RUser.findByAccount(accountId)
          check = optUser.map(_.password).map(p => PasswordCrypt.check(current, p))
          n <-
            check
              .filter(identity)
              .traverse(_ =>
                RUser.updatePassword(accountId, PasswordCrypt.crypt(newPass))
              )
          res = check match {
            case Some(true) =>
              if (n.getOrElse(0) > 0) PassChangeResult.success
              else PassChangeResult.updateFailed
            case Some(false) =>
              PassChangeResult.passwordMismatch
            case None =>
              PassChangeResult.userNotFound
          }
        } yield res

        store.transact(q)
      }

      def getContacts(
          collective: Ident,
          query: Option[String],
          kind: Option[ContactKind]
      ): Stream[F, RContact] =
        store.transact(QCollective.getContacts(collective, query, kind))

      def findEnabledSource(sourceId: Ident): F[Option[RSource]] =
        store.transact(RSource.findEnabled(sourceId))
    })
}
