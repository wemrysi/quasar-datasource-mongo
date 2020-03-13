/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongo

import slamdata.Predef._

import cats.data.OptionT
import cats.effect.{IO, Resource}

import fs2.Stream

import org.bson._
import org.specs2.matcher.MatchResult
import org.specs2.specification.core._

import quasar.{EffectfulQSpec, ScalarStages}
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.{QueryResult, ResourceError}
import quasar.physical.mongo.MongoResource.Collection
import quasar.qscript.InterpretedRead

import shims._
import testImplicits._

class MongoDataSourceSpec extends EffectfulQSpec[IO] {
  def mkDataSource: Resource[IO, MongoDataSource[IO]] =
    MongoSpec.mkMongo.map(new MongoDataSource[IO](_))

  val mkInaccessibleDataSource: Resource[IO, MongoDataSource[IO]] =
    MongoSpec.mkMongoInvalidPort.map(new MongoDataSource[IO](_))

  step(MongoSpec.setupDB.unsafeRunSync())

  private def iRead[A](path: A): InterpretedRead[A] = InterpretedRead(path, ScalarStages.Id)

  def connFailed[A]: PartialFunction[Either[Throwable, A], MatchResult[Any]] = {
    case Left(t) => ResourceError.throwableP.getOption(t) must beLike {
      case Some(ResourceError.connectionFailed(p, Some("Timed out connecting to server"), Some(_))) => p === ResourcePath.root()
    }
  }

  def pathNotFound[A](path: ResourcePath): PartialFunction[Either[Throwable, A], MatchResult[Any]] = {
    case Left(t) => ResourceError.throwableP.getOption(t) must_=== Some(ResourceError.pathNotFound(path))
  }

  def assertResourceError[A](res: Either[Throwable, A], expected: PartialFunction[Either[Throwable, A], MatchResult[Any]]) =
    res must beLike(expected)

  "evaluation of" >> {
    def assertPathNotFound(ds: Resource[IO, MongoDataSource[IO]], path: ResourcePath): MatchResult[Either[Throwable, Option[QueryResult[IO]]]] = {
      ds.use(_.loadFull(iRead(path)).value).attempt.unsafeRunSync() must beLike(pathNotFound(path))
    }

    def assertConnectionFailed(ds: Resource[IO, MongoDataSource[IO]], path: ResourcePath): MatchResult[Either[Throwable, Option[QueryResult[IO]]]] =
      ds.use(_.loadFull(iRead(path)).value).attempt.unsafeRunSync() must beLike(connFailed)

    "root raises path not found" >>
      assertPathNotFound(mkDataSource, ResourcePath.root())

    "root of inaccessible datasource raises path not found" >>
      assertPathNotFound(mkInaccessibleDataSource, ResourcePath.root())

    "existing database raises path not found" >>
      Fragment.foreach(MongoSpec.correctDbs)(db =>
        s"checking ${db.name}" >> assertPathNotFound(mkDataSource, db.resourcePath)
      )

    "non-existing database raises path not found" >>
      Fragment.foreach(MongoSpec.incorrectDbs)(db =>
        s"checking ${db.name}" >> assertPathNotFound(mkDataSource, db.resourcePath)
      )

    "inaccessible database raises path not found" >>
      Fragment.foreach(MongoSpec.incorrectDbs)(db =>
        s"checking ${db.name}" >> assertPathNotFound(mkInaccessibleDataSource, db.resourcePath)
      )

    "existing collections is collection content" >>
      Fragment.foreach(MongoSpec.correctCollections)(coll =>
        s"checking ${coll.database.name} :: ${coll.name}" >>* {
          def checkBson(col: Collection, bsons: List[Any]): Boolean = bsons match {
            case (bson: BsonValue) :: List() =>
              (bson.asDocument().get(coll.name).asString().getValue() === coll.database.name).isSuccess
            case _ => false
          }

          mkDataSource use { ds =>
            val fStream: OptionT[IO, Stream[IO, Any]] =
              ds.loadFull(iRead(coll.resourcePath)) map {
                case QueryResult.Parsed(_, data, _) => data
                case QueryResult.Typed(_, data, _) => data
                case QueryResult.Stateful(_, _, _, _, _) => Stream.empty
              }

            Stream.force(fStream getOrElse Stream.empty)
              .compile.toList
              .map(checkBson(coll, _))
          }
        })

    "non-existing collection raises path not found" >>
      Fragment.foreach(MongoSpec.incorrectCollections)(col =>
        s"checking ${col.database.name} :: ${col.name}" >> assertPathNotFound(mkDataSource, col.resourcePath)
      )

    "inaccessible collection raises connection failed" >>
      Fragment.foreach(MongoSpec.incorrectCollections.headOption.toList)(col =>
        s"checking ${col.database.name} :: ${col.name}" >> assertConnectionFailed(mkInaccessibleDataSource, col.resourcePath)
      )
  }

  "prefixedChildPaths" >> {
    def assertPrefixed(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath, expected: List[(ResourceName, ResourcePathType.Physical)]): IO[MatchResult[Set[(ResourceName, ResourcePathType.Physical)]]] =
      datasource use { ds =>
        val fStream = ds.prefixedChildPaths(path).map(_ getOrElse Stream.empty)
        Stream.force(fStream).compile.toList.map(_.toSet must contain(expected.toSet))
      }

    def assertConnectionFailed(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(ds => {
        val fStream = ds.prefixedChildPaths(path).map(_ getOrElse Stream.empty)
        Stream.force(fStream).compile.toList
      }).attempt.unsafeRunSync() must beLike(connFailed)

    def assertNone(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(_.prefixedChildPaths(path).map(_ must_=== None))

    def assertEmptyStream(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(_.prefixedChildPaths(path).map(_ must_=== Some(Stream.empty)))

    "children of root are databases" >>*
      assertPrefixed(
        mkDataSource,
        ResourcePath.root(),
        MongoSpec.correctDbs.map(db => (ResourceName(db.name), ResourcePathType.prefix)))

    "children of inaccessible root raises connection failed" >>
      assertConnectionFailed(
        mkInaccessibleDataSource,
        ResourcePath.root())

    "children of existing database are collections" >> {
      val expected =
        MongoSpec.cols.map(colName => (ResourceName(colName), ResourcePathType.leafResource))
      Fragment.foreach(MongoSpec.dbs){ db =>
        s"checking $db" >>* assertPrefixed(mkDataSource, ResourcePath.root() / ResourceName(db), expected)
      }
    }

    "children of non-existing database are none" >>
      Fragment.foreach(MongoSpec.incorrectDbs)(db =>
        s"checking ${db.name}" >>* assertNone(mkDataSource, db.resourcePath))

    "children of inaccessible database raises connection failed" >>
      Fragment.foreach(MongoSpec.incorrectDbs)(db =>
        s"checking ${db.name}" >> assertConnectionFailed(mkInaccessibleDataSource, db.resourcePath))

    "children of existing collection are empty stream" >>
      Fragment.foreach (MongoSpec.correctCollections)(col =>
        s"checking ${col.database.name} :: ${col.name}" >>* assertEmptyStream(mkDataSource, col.resourcePath))

    "children of non-existing collection are none" >>
      Fragment.foreach (MongoSpec.incorrectCollections)(col =>
        s"checking ${col.database.name} :: ${col.name}" >>* assertNone(mkDataSource, col.resourcePath))

    "children of inaccessible collection raises connection failed" >>
      Fragment.foreach (MongoSpec.incorrectCollections)(col =>
        s"checking ${col.database.name} :: ${col.name}" >> assertConnectionFailed(mkInaccessibleDataSource, col.resourcePath))
  }

  "pathIsResource" >> {
    def assertNoResource(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(_.pathIsResource(path).map(!_))

    def assertResource(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(_.pathIsResource(path))

    def assertConnectionFailed(datasource: Resource[IO, MongoDataSource[IO]], path: ResourcePath) =
      datasource.use(_.pathIsResource(path)).attempt.unsafeRunSync() must beLike(connFailed)

    "returns false for root" >>* assertNoResource(mkDataSource, ResourcePath.root())

    "returns false for inaccessible root" >>* assertNoResource(mkInaccessibleDataSource, ResourcePath.root())

    "returns false for existing database" >> Fragment.foreach(MongoSpec.correctDbs) { db =>
      s"checking ${db.name}" >>* assertNoResource(mkDataSource, db.resourcePath)
    }

    "returns false for non-existing database" >> Fragment.foreach(MongoSpec.incorrectDbs) { db =>
      s"checking ${db.name}" >>* assertNoResource(mkDataSource, db.resourcePath)
    }

    "returns false for inaccessible database" >> Fragment.foreach(MongoSpec.correctDbs) { db =>
      s"checking ${db.name}" >>* assertNoResource(mkInaccessibleDataSource, db.resourcePath)
    }

    "returns true for existing collections" >> Fragment.foreach(MongoSpec.correctCollections) { col =>
      s"checking ${col.database.name} :: ${col.name}" >>* assertResource(mkDataSource, col.resourcePath)
    }

    "returns false for non-existing collections" >> Fragment.foreach(MongoSpec.incorrectCollections) { col =>
      s"checking ${col.database.name} :: ${col.name}" >>* assertNoResource(mkDataSource, col.resourcePath)
    }

    "raises connection failed for inaccessible collections" >> Fragment.foreach(MongoSpec.incorrectCollections) { col =>
      s"checking ${col.database.name} :: ${col.name}" >> assertConnectionFailed(mkInaccessibleDataSource, col.resourcePath)
    }
  }
}
