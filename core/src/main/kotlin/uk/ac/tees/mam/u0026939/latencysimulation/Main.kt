package uk.ac.tees.mam.u0026939.latencysimulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.assets.disposeSafely
import ktx.assets.toInternalFile
import ktx.async.KtxAsync
import ktx.graphics.LetterboxingViewport
import ktx.graphics.use
import java.util.concurrent.ConcurrentLinkedQueue

class Main : KtxGame<KtxScreen>() {
    override fun create() {
        KtxAsync.initiate()

        addScreen(FirstScreen())
        setScreen<FirstScreen>()
    }
}

class MyCharacter(private var position: Vector2, private var velocity: Vector2, val sprite: Sprite) {

    private var targetPosition: Vector2? = null
    private var speed = 400f // Units per second, you can adjust it as needed

    init {
        sprite.setSize(32f, 32f)
    }

    fun moveTo(target: Vector2) {
        targetPosition = target
        velocity = target.cpy().sub(position).nor().scl(speed)
    }

    fun update(deltaTime: Float) {
        targetPosition?.let {
            val distanceToTarget = it.cpy().sub(position).len()
            val maxMoveDistance = speed * deltaTime

            if (distanceToTarget <= maxMoveDistance) {
                position.set(it)
                velocity.set(0f, 0f)
                targetPosition = null
            } else {
                position.add(velocity.cpy().scl(deltaTime))
            }
        }
        sprite.setPosition(position.x, position.y)
    }

    fun draw(batch: SpriteBatch) {
        sprite.draw(batch)
    }
}

class FirstScreen : KtxScreen {
    private val image = Texture("circle.png".toInternalFile(), true).apply { setFilter(Linear, Linear) }
    private val batch = SpriteBatch()
    private lateinit var p1 : MyCharacter
    private lateinit var p2 : MyCharacter
    private lateinit var viewport: FitViewport
    private val queue: ConcurrentLinkedQueue<Vector2> = ConcurrentLinkedQueue()

    override fun show() {
        super.show()
        val camera = OrthographicCamera()
        camera.setToOrtho(true, 1200f, 540f)
        viewport = FitViewport(1200f, 540f, camera)
        p1 = MyCharacter(Vector2(50f, 50f), Vector2(0f, 0f), Sprite(image))
        p2 = MyCharacter(Vector2(350f, 350f), Vector2(0f, 0f), Sprite(image))
        p2.sprite.color = com.badlogic.gdx.graphics.Color.BLUE
        // make a coroutine to simulate the network latency
        Thread {
            while (true) {
                queue.poll()?.let {
                    p2.moveTo(it.add(300f, 0f))
                }
//                Thread.sleep(20)
            }
        }.start()
    }

    override fun render(delta: Float) {
        input()
        update(delta)
        draw()
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        viewport.update(width, height, true)
    }
    private fun draw() {
        clearScreen(red = 0.8f, green = 0.7f, blue = 0.7f)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        batch.use {
            p1.draw(it)
            p2.draw(it)
        }
    }

    private fun update(delta: Float) {
        p1.update(delta)
        p2.update(delta)
    }

    private fun input() {
        if (Gdx.input.isTouched) {
            val target = Vector2(Gdx.input.x.toFloat() - 32, Gdx.input.y.toFloat() - 32)
            viewport.unproject(target)
            p1.moveTo(target)
            // "enqueue" the target position for the second player; do it in a delayed coroutine to simulate network latency
            // The scope of the coroutine should be the same as the game loop
            CoroutineScope(Dispatchers.IO).launch {
                delay(20)
                queue.add(target)
            }
//            Gdx.app.log("input", "$target.x $target.y")
        }
    }

    override fun dispose() {
        image.disposeSafely()
        batch.disposeSafely()
    }
}
