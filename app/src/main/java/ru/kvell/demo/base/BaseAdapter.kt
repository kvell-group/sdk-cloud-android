package ru.kvell.demo.base

import androidx.recyclerview.widget.RecyclerView

abstract class BaseAdapter<VH : RecyclerView.ViewHolder?> : RecyclerView.Adapter<VH>()