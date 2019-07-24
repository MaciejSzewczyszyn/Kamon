/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
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

package kamon.instrumentation.akka.instrumentations

import akka.actor.{ActorRef, ActorSystem}
import akka.instrumentation.ReplaceWithMethodInterceptor

import kamon.Kamon
import kamon.context.Storage.Scope
import kamon.instrumentation.akka.instrumentations.HasActorMonitor.actorMonitor
import kamon.instrumentation.context.{HasContext, HasTimestamp}
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.asm.Advice
import kanela.agent.libs.net.bytebuddy.asm.Advice.{Argument, OnMethodEnter, OnMethodExit, This}

import scala.util.Properties

class ActorInstrumentation extends InstrumentationBuilder {

  /**
    * This is where most of the Actor processing magic happens. Handling of messages, errors and system messages.
    */
  onType("akka.actor.ActorCell")
    .mixin(classOf[HasActorMonitor.Mixin])
    .advise(isConstructor, ActorCellConstructorAdvice)
    .advise(method("invoke"), classOf[ActorCellInvokeAdvice])
    .advise(method("handleInvokeFailure"), HandleInvokeFailureMethodAdvice)
    .advise(method("sendMessage").and(takesArguments(1)), SendMessageAdvice)
    .advise(method("terminate"), TerminateMethodAdvice)
    .advise(method("swapMailbox"), ActorCellSwapMailboxAdvice)
    .advise(method("invokeAll$1"), InvokeAllMethodInterceptor)

  /**
    * Ensures that the Context is properly propagated when messages are temporarily stored on an UnstartedCell.
    */
  onType("akka.actor.UnstartedCell")
    .mixin(classOf[HasActorMonitor.Mixin])
    .advise(isConstructor, RepointableActorCellConstructorAdvice)
    .advise(method("sendMessage").and(takesArguments(1)), SendMessageAdvice)
    .intercept(method("replaceWith"), ReplaceWithMethodInterceptor)

}

object ActorInstrumentation {

  val (unstartedCellQueueField, unstartedCellLockField, systemMsgQueueField) = {
    val unstartedCellClass = AkkaPrivateAccess.unstartedActorCellClass()

    val prefix = Properties.versionNumberString.split("\\.").take(2).mkString(".") match {
      case _@ "2.11" => "akka$actor$UnstartedCell$$"
      case _@ "2.12" => ""
      case _@ "2.13" => ""
      case v         => throw new IllegalStateException(s"Incompatible Scala version: $v")
    }

    val queueField = unstartedCellClass.getDeclaredField(prefix+"queue")
    val lockField = unstartedCellClass.getDeclaredField("lock")
    val sysQueueField = unstartedCellClass.getDeclaredField(prefix+"sysmsgQueue")
    queueField.setAccessible(true)
    lockField.setAccessible(true)
    sysQueueField.setAccessible(true)

    (queueField, lockField, sysQueueField)
  }

}

trait HasActorMonitor {
  def actorMonitor: ActorMonitor
  def setActorMonitor(actorMonitor: ActorMonitor): Unit
}

object HasActorMonitor {

  class Mixin(var actorMonitor: ActorMonitor) extends HasActorMonitor {
    override def setActorMonitor(actorMonitor: ActorMonitor): Unit =
      this.actorMonitor = actorMonitor
  }

  def actorMonitor(cell: Any): ActorMonitor =
    cell.asInstanceOf[HasActorMonitor].actorMonitor
}

object ActorCellSwapMailboxAdvice {

  @Advice.OnMethodEnter
  def enter(@Advice.This cell: Any, @Advice.Argument(0) newMailbox: Any): Boolean = {
    val isShuttingDown = AkkaPrivateAccess.isDeadLettersMailbox(cell, newMailbox)
    if(isShuttingDown)
      actorMonitor(cell).onTerminationStart()

    isShuttingDown
  }

  @Advice.OnMethodExit
  def exit(@Advice.This cell: Any, @Advice.Return oldMailbox: Any, @Advice.Enter isShuttingDown: Boolean): Unit = {
    if(oldMailbox != null && isShuttingDown) {
      actorMonitor(cell).onDroppedMessages(AkkaPrivateAccess.mailboxMessageCount(oldMailbox))
    }
  }
}

object InvokeAllMethodInterceptor {

  @Advice.OnMethodEnter
  def enter(@Advice.Argument(0) message: Any): Option[Scope] =
    message match {
      case m: HasContext => Some(Kamon.storeContext(m.context))
      case _ => None
    }

  @Advice.OnMethodExit
  def exit(@Advice.Enter scope: Option[Scope]): Unit =
    scope.foreach(_.close())
}

object SendMessageAdvice {

  @OnMethodEnter(suppress = classOf[Throwable])
  def onEnter(@This cell: Any, @Argument(0) envelope: Object): Unit = {

    val instrumentation = actorMonitor(cell)
    envelope.asInstanceOf[HasContext].setContext(instrumentation.captureEnvelopeContext())
    envelope.asInstanceOf[HasTimestamp].setTimestamp(instrumentation.captureEnvelopeTimestamp())
  }
}

object RepointableActorCellConstructorAdvice {

  @Advice.OnMethodExit(suppress = classOf[Throwable])
  def onExit(@This cell: Any, @Argument(0) system: ActorSystem, @Argument(1) ref: ActorRef, @Argument(3) parent: ActorRef): Unit =
    cell.asInstanceOf[HasActorMonitor].setActorMonitor(ActorMonitor.from(cell, ref, parent, system))
}

object ActorCellConstructorAdvice {

  @OnMethodExit(suppress = classOf[Throwable])
  def onExit(@This cell: Any, @Argument(0) system: ActorSystem, @Argument(1) ref: ActorRef, @Argument(4) parent: ActorRef): Unit =
    cell.asInstanceOf[HasActorMonitor].setActorMonitor(ActorMonitor.from(cell, ref, parent, system))
}

object HandleInvokeFailureMethodAdvice {

  @OnMethodEnter(suppress = classOf[Throwable])
  def onEnter(@This cell: Any, @Argument(1) failure: Throwable): Unit =
    actorMonitor(cell).onFailure(failure)

}

object TerminateMethodAdvice {

  @OnMethodEnter(suppress = classOf[Throwable])
  def onEnter(@This cell: Any): Unit = {
    actorMonitor(cell).cleanup()

    if (AkkaPrivateAccess.isRoutedActorCell(cell)) {
      cell.asInstanceOf[HasRouterMonitor].routerMonitor.cleanup()
    }
  }
}
