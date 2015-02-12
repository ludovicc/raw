package raw

case class LocationError(err: String) extends RawException(err)

case class Source(tipe: CollectionType, location: DataLocation)

class World(val catalog: Map[String, Source], val userTypes: Map[String, Type] = Map()) {
  def getSource(name: String): Source = catalog.get(name) match {
    case Some(Source(tipe, location)) => Source(tipe, location)
    case None => throw LocationError(s"Unknown location $name")
  }
}


// TODO: Later on, add the following to the World:
//   val activeQueries: Set[...] /* Set of queries in execution */
//   val pendingQueries: List[...] /* List of queries waiting to execute because the infrastructure is busy or because they require data from unavailable nodes */
//   val optimizedQueries: ... /* List of recently optimized queries (not to call optimizer again?)
//   val activeNodes: Set[...] /* Set of active nodes */
