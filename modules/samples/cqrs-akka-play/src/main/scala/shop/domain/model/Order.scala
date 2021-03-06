package shop.domain.model

import java.time.OffsetDateTime

import io.strongtyped.funcqrs._
import io.strongtyped.funcqrs.dsl.BehaviorDsl._
import io.strongtyped.funcqrs.json.TypedJson.{TypeHintFormat, _}
import play.api.libs.json._

sealed trait Status

case object Open extends Status

case object Executed extends Status

case object Cancelled extends Status

case class Order(number: OrderNumber,
                 customerId: CustomerId,
                 products: Map[ProductNumber, Quantity] = Map(),
                 status: Status = Open) extends Aggregate {

  type Identifier = OrderNumber
  def identifier: Identifier = number
  type Protocol = OrderProtocol.type

  def addProduct(productNumber: ProductNumber): Order = {
    val qty = products.getOrElse(productNumber, Quantity(0))
    val newProducts = products + (productNumber -> qty.plusOne)
    copy(products = newProducts)
  }

  def removeProduct(productNumber: ProductNumber): Order = {

    val maybeQuantity =
      products.get(productNumber).flatMap {
        case Quantity(1) => None
        case Quantity(0) => None
        case qty         => Some(qty.minusOne)
      }

    val newProducts =
      maybeQuantity.map { qty =>
        // if it's a Some, we have a qty >= 1, we update the map
        products + (productNumber -> qty)
      }.getOrElse {
        // if quantity reaches 0, we remove it from Map
        products.filterKeys(_ != productNumber)
      }

    copy(products = newProducts)
  }
}

case class Quantity(num: Int) {
  def plusOne = Quantity(num + 1)
  def minusOne = Quantity(num - 1)
}

object Quantity {
  implicit val format = Json.format[Quantity]
}

object Order {

  val tag = Tags.aggregateTag("order")
  val dependentView = Tags.dependentViews("OrderView")


  def behavior(orderNum: OrderNumber): Behavior[Order] = {

    import OrderProtocol._
    val metadata = Metadata.metadata(tag, dependentView)

    behaviorFor[Order].whenConstructing { it =>

      it.emitsEvent {
        case cmd: CreateOrder => OrderCreated(cmd.customerId, metadata(orderNum))
      }

      it.acceptsEvents {
        case evt: OrderCreated => Order(orderNum, evt.customerId)
      }

    }.whenUpdating { it =>

      it.emitsSingleEvent {

        case (order, cmd: AddProduct) if order.status == Open =>
          ProductAdded(cmd.productNumber, metadata(orderNum))

        case (order, cmd: RemoveProduct) if order.status == Open =>
          ProductRemoved(cmd.productNumber, metadata(orderNum))

        case (order, cmd: Execute.type) if order.status == Open =>
          OrderExecuted(OffsetDateTime.now(), metadata(orderNum))

        case (order, cmd: Cancel.type) if order.status == Open =>
          OrderCancelled(OffsetDateTime.now(), metadata(orderNum))
      }

      it.rejectsCommands {

        case (order, cmd: Execute.type) if order.status == Executed =>
          new CommandException(s"Order is already executed")

        case (order, cmd: Execute.type) if order.status == Cancelled =>
          new CommandException(s"Can't execute a cancelled order")

        case (order, cmd: Cancel.type) if order.status == Executed =>
          new CommandException(s"Can't cancel an executed order")

        case (order, _) if order.status == Executed =>
          new CommandException(s"Can't modify an executed order")

        case (order, _) if order.status == Cancelled =>
          new CommandException(s"Can't modify a cancelled order")


      }

      it.acceptsEvents {

        case (order, evt: ProductAdded) => order.addProduct(evt.productNumber)

        case (order, evt: ProductRemoved) => order.removeProduct(evt.productNumber)

        case (order, evt: OrderExecuted)  => order.copy(status = Executed)
        case (order, evt: OrderCancelled) => order.copy(status = Cancelled)
      }
    }
  }
}

case class OrderNumber(value: String) extends AggregateIdentifier

object OrderNumber {
  implicit val format = Json.format[OrderNumber]
  def fromAggregateId(aggregateId: AggregateIdentifier) = OrderNumber.fromString(aggregateId.value)
  def fromString(id: String) = OrderNumber(id)
}

object OrderProtocol extends ProtocolDef.Commands with ProtocolDef.Events {

  sealed trait OrderCommand extends ProtocolCommand

  case class CreateOrder(customerId: CustomerId) extends OrderCommand

  case class AddProduct(productNumber: ProductNumber) extends OrderCommand

  case class RemoveProduct(productNumber: ProductNumber) extends OrderCommand

  case object Execute extends OrderCommand

  case object Cancel extends OrderCommand

  implicit val commandFormats = {
    TypeHintFormat[OrderCommand](
      Json.format[CreateOrder].withTypeHint("Order.Create"),
      Json.format[AddProduct].withTypeHint("Order.AddProduct"),
      Json.format[RemoveProduct].withTypeHint("Order.RemoveProduct"),
      hintedObject(Execute, "Order.Execute"),
      hintedObject(Cancel, "Order.Cancel")
    )

  }


  sealed trait OrderEvent extends ProtocolEvent with MetadataFacet

  case class OrderCreated(customerId: CustomerId, metadata: Metadata) extends OrderEvent

  case class ProductAdded(productNumber: ProductNumber, metadata: Metadata) extends OrderEvent

  case class ProductRemoved(productNumber: ProductNumber, metadata: Metadata) extends OrderEvent

  case class OrderExecuted(date: OffsetDateTime, metadata: Metadata) extends OrderEvent

  case class OrderCancelled(date: OffsetDateTime, metadata: Metadata) extends OrderEvent

}