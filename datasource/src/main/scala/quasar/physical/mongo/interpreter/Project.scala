/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.physical.mongo.interpreter

import slamdata.Predef._

import quasar.physical.mongo.expression._

import scalaz.{MonadState, Scalaz, ApplicativePlus}, Scalaz._

object Project {
  def apply[F[_]: MonadInState](fld: Projection): F[List[Pipe]] =
    MonadState[F, InterpretationState].gets(_.uniqueKey) flatMap { uniqueKey =>
      val tmpKey = uniqueKey.concat("_project")
      val projection = Projection.key(uniqueKey) + fld
      val matcher: Pipe = Pipeline.$match(Map(projection.toKey -> O.$exists(O.bool(true))))
      val move: Pipe = Pipeline.$project(Map(tmpKey -> O.projection(projection)))
      val project: Pipe = Pipeline.$project(Map(uniqueKey -> O.key(tmpKey)))
      List(matcher, move, project).point[F]
    }
}
