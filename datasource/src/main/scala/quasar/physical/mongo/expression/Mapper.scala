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

package quasar.physical.mongo.expression

import slamdata.Predef._

import org.bson._

trait Mapper extends Product with Serializable

object Mapper {
  final case class Nest(str: String) extends Mapper
  final case object Identity extends Mapper

  def bson(mapper: Mapper)(inp: BsonValue): BsonValue = mapper match {
    case Nest(str) => inp.asDocument().get(str)
    case Identity => inp
  }

  def projection(mapper: Mapper)(inp: Projection): Projection = mapper match {
    case Nest(str) => Projection.key(str) + inp
    case Identity => inp
  }
}
