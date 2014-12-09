/*
 * =========================================================================================
 * Copyright © 2014 the khronus project <https://github.com/hotels-tech/khronus>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.despegar.khronus.util

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{ UnsafeInput, UnsafeOutput }
import org.objenesis.strategy.StdInstantiatorStrategy

trait Serializer[A] {

  def deserialize(array: Array[Byte]): A

  def serialize(anObject: A): Array[Byte]
}

class KryoSerializer[T](name: String, hintedClasses: List[Class[_]] = List.empty) extends Serializer[T] {

  val kryoFactory = new Factory[Kryo] {
    def newInstance(): Kryo = {
      val kryo = new Kryo()
      kryo.setReferences(false)
      kryo.setInstantiatorStrategy(new StdInstantiatorStrategy)
      hintedClasses.foreach(hintedClass ⇒ kryo.register(hintedClass))
      kryo
    }
  }

  val pool = new KryoPool(name, kryoFactory, 10)

  override def serialize(anObject: T): Array[Byte] = {
    val kryoHolder = pool.take()
    try {
      val kryo = kryoHolder
      val outputStream = new ByteArrayOutputStream()
      val output = new UnsafeOutput(outputStream)
      kryo.writeClassAndObject(output, anObject)
      output.flush()
      val bytes = outputStream.toByteArray()
      output.close()
      bytes
    } finally {
      pool.release(kryoHolder)
    }
  }

  override def deserialize(bytes: Array[Byte]): T = {
    deserialize(new UnsafeInput(bytes))
  }

  private def deserialize(input: UnsafeInput): T = {
    val kryo = pool.take()
    try {
      kryo.readClassAndObject(input).asInstanceOf[T]
    } finally {
      pool.release(kryo)
    }
  }
}

trait Factory[T] {
  def newInstance(): T
}

class KryoPool(name: String, factory: Factory[Kryo], initInstances: Int) {
  val instances = new AtomicLong()
  instances.set(initInstances)
  val maxInstances = initInstances * 2
  val objects = new ConcurrentLinkedQueue[Kryo]()

  (1 to initInstances) foreach { _ ⇒ objects.offer(factory.newInstance()) }
  def take(): Kryo = {
    val pooledKryo = objects.poll()
    if (pooledKryo == null) {
      return factory.newInstance()
    }
    instances.decrementAndGet()
    return pooledKryo
  }
  def release(kh: Kryo) = {
    if (instances.intValue() < maxInstances) {
      instances.incrementAndGet()
      objects.offer(kh)
    }
  }
  def close() = {
    objects.clear()
  }
}

class KryoSerializerFactory {
  def create[T](name: String, hintedClasses: List[Class[_]]) = {
    new KryoSerializer[T](name, hintedClasses)
  }
}