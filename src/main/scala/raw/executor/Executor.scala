package raw.executor

import raw.{RawException, QueryError, QueryResult, World}
import raw.algebra.PhysicalAlgebra

abstract class RawExecutorException extends RawException
case class RawExecutorRuntimeException(message: String) extends RawExecutorException {
  override def toString() = s"raw execution error: $message"
}

abstract class Executor {
  def execute(root: PhysicalAlgebra.AlgebraNode, world: World): Either[QueryError,QueryResult]
}
