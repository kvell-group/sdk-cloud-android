package ru.kvell.demo.models

import com.google.gson.annotations.SerializedName
import ru.kvell.demo.R

data class Product(
		@SerializedName("id") val id: String = "1",
		@SerializedName("name") val name: String = "Букет \"Нежность\"",
		@SerializedName("price") val price: String = "1",
		@SerializedName("image") val image: Int = R.drawable.product)
