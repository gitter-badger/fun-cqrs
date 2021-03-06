package shop.domain.service

import com.softwaremill.macwire._
import io.strongtyped.funcqrs.{HandleEvent, Logging, LoggingSuffix, Projection}
import shop.domain.model.OrderProtocol.{OrderCreated, ProductAdded, ProductRemoved}
import shop.domain.model._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class OrderViewProjection(orderRepo: OrderViewRepo,
                          productRepo: ProductViewRepo @@ OrderView.type,
                          customerRepo: CustomerViewRepo @@ OrderView.type) extends Projection with Logging {


  // reuse projections with other repos
  val productProjection = new ProductViewProjection(productRepo) with LoggingSuffix {
    val suffix = "OrderView"
  }

  val customerProjection = new CustomerViewProjection(customerRepo) with LoggingSuffix {
    val suffix = "OrderView"
  }


  def receiveEvent: HandleEvent = {

    case e: ProductProtocol.ProductEvent =>
      logger.debug(s"received product event $e")
      productProjection.onEvent(e)

    case e: CustomerProtocol.CustomerEvent =>
      logger.debug(s"received customer event $e")
      customerProjection.onEvent(e)

    case e: OrderProtocol.OrderCreated   => create(e)
    case e: OrderProtocol.ProductAdded   => addProduct(e)
    case e: OrderProtocol.ProductRemoved => removeProduct(e)

    case e: OrderProtocol.OrderExecuted  => changeStatus(number(e), Executed)
    case e: OrderProtocol.OrderCancelled => changeStatus(number(e), Cancelled)

  }

  private def number(evt: OrderProtocol.OrderEvent) = {
    OrderNumber.fromAggregateId(evt.aggregateId)
  }

  def create(evt: OrderCreated): Future[Unit] = {
    logger.debug(s"creating order $evt")
    customerRepo.find(evt.customerId).flatMap { customer =>
      orderRepo.save(OrderView(number(evt), customer.name))
    }
  }

  def changeStatus(num: OrderNumber, status: Status): Future[Unit] = {
    logger.debug(s"order [$num] status updated to $status ")
    for {
      order <- orderRepo.find(num)
      updatedOrder = order.copy(status = status)
      _ <- orderRepo.save(updatedOrder)
    } yield ()
  }


  def addProduct(evt: ProductAdded): Future[Unit] = {

    val num = number(evt)
    logger.debug(s"adding product ${evt.productNumber} to order $num")

    for {
      order <- orderRepo.find(num)
      product <- productRepo.find(evt.productNumber)
      newItem = OrderItem(evt.productNumber, product.name, product.price, Quantity(1))
      _ <- orderRepo.save(order.addItem(newItem))
    } yield ()
  }


  def removeProduct(evt: ProductRemoved): Future[Unit] = {

    val num = number(evt)
    logger.debug(s"removing product ${evt.productNumber} from order $num")

    for {
      order <- orderRepo.find(num)
      product <- productRepo.find(evt.productNumber)
      _ <- orderRepo.save(order.removeItem(evt.productNumber))
    } yield ()
  }
}
