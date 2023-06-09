package org.jash.mylibrary.activity

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.jash.mylibrary.annotations.OnThread
import org.jash.mylibrary.annotations.Subscribe
import org.jash.mylibrary.processor
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.functions
private fun parse(owner: LifecycleOwner):MutableList<Disposable> {
    return owner.javaClass.kotlin.let { clazz ->
        clazz.functions.filter {
            it.findAnnotations(Subscribe::class).isNotEmpty()
        }
            .map {
                processor.ofType((it.parameters[1].type.classifier as KClass<*>).java)
                    .observeOn(
                        when (it.findAnnotations(Subscribe::class)[0].onThread) {
                            OnThread.MAIN_THREAD -> AndroidSchedulers.mainThread()
                            OnThread.IO_THREAD -> Schedulers.io()
                        }
                    )
                    .let { flow ->
                        val filter = it.findAnnotations(Subscribe::class)[0].filter
                        if (filter.isEmpty()) flow else
                            flow.filter { data ->
                                filter.map { name ->
                                    clazz.functions.find { f -> f.name == name }?.call(owner, data)
                                }.filterIsInstance<Boolean>().reduce { b1, b2 -> b1 && b2 }
                            }
                    }
                    .subscribe { data -> it.call(owner, data) }
            }.toMutableList()
    }
}

class SafeSubscribe(val d: MutableList<Disposable>) : LifecycleEventObserver {
    constructor(vararg ds:Disposable) : this(mutableListOf(*ds))
    constructor(owner: LifecycleOwner) : this(mutableListOf()) {
        owner.lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                d.clear()
                d.addAll(parse(source))
            }
            Lifecycle.Event.ON_PAUSE -> d.filter { !it.isDisposed }.forEach { it.dispose() }
            else -> {}
        }
    }

}