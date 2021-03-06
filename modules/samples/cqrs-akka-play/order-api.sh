

get.Order() {
  shop.api.get order/$1
}


list.Orders() {
  shop.api.get orders
}

create.Order() {
	cat << EOF > last.json
{
  "customerId": { "value" : "$2" },
  "_type": "Order.Create"
}
EOF
    shop.api.put order/$1
}

order.AddProduct() {
cat << EOF > last.json
{
  "productNumber": { "number": "$2" },
  "_type": "Order.AddProduct"
}
EOF

  shop.api.patch order/$1
}

order.RemoveProduct() {
cat << EOF > last.json
{
  "productNumber": { "number": "$2" },
  "_type": "Order.RemoveProduct"
}
EOF

  shop.api.patch order/$1
}

order.Cancel() {
cat << EOF > last.json
{
  "_type": "Order.Cancel"
}
EOF

  shop.api.patch order/$1
}


order.Execute() {
cat << EOF > last.json
{
  "_type": "Order.Execute"
}
EOF

  shop.api.patch order/$1
}
