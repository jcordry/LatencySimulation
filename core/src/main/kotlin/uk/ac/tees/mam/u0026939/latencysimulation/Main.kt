package uk.ac.tees.mam.u0026939.latencysimulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.badlogic.gdx.utils.viewport.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ktx.app.KtxGame
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.assets.disposeSafely
import ktx.assets.toInternalFile
import ktx.async.KtxAsync
import ktx.graphics.use
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.random.Random


/* TODO:
    - implement and simulate the dead reckoning algorithm (and at least the lerping aspect)
 */

class Main : KtxGame<KtxScreen>() {
    override fun create() {

        KtxAsync.initiate()

        addScreen(GameScreen())
        setScreen<GameScreen>()
    }
}

class MyCharacter(var position: Vector2, val sprite: Sprite) {
    private var targetPosition: Vector2? = null
    private var speed = 400f // Units per second, you can adjust it as needed
    var velocity: Vector2 = Vector2(0f, 0f)
    var lastUpdateTime = 0f

    init {
        sprite.setSize(32f, 32f)
        sprite.setOriginCenter()
    }

    fun moveTo(target: Vector2) {
        targetPosition = target
        velocity.set(target.cpy().sub(position).nor().scl(speed))
    }

    fun update(deltaTime: Float) {
        targetPosition?.let { target ->
            val direction = target.cpy().sub(position).nor()
            val distance = position.dst(target)
            val step = speed * deltaTime

            if (distance > step) {
                position.add(direction.scl(step)) // Move towards target
            } else {
                position.set(target) // Snap to target when close
                velocity.setZero()
                targetPosition = null
            }
        }
    }

    fun draw(batch: SpriteBatch) {
        sprite.setPosition(position.x, position.y)
        sprite.draw(batch)
    }
}

class DeadReckoning(private val player2: MyCharacter) {
    private val updateThreshold = 10f // Max error before correction
    private var lastKnownPosition = Vector2()
    private var lastUpdateTimestamp = 0f

    fun update(deltaTime: Float) {
        // Predict Player 2's movement
        player2.update(deltaTime)
    }

    fun onNetworkUpdate(newPosition: Vector2, newTarget: Vector2, timestamp: Float) {
        val latency = timestamp - lastUpdateTimestamp
        lastUpdateTimestamp = timestamp

        val predictedPosition = lastKnownPosition.cpy().add(player2.velocity.cpy().scl(latency))
        val error = newPosition.dst(predictedPosition)

        if (error > updateThreshold) {
            // Large error -> Snap to position
            player2.position.set(newPosition)
        } else {
            // Small error -> Smooth correction
            player2.position.interpolate(newPosition, 0.1f, Interpolation.linear)
        }

        // Update movement towards target
        player2.moveTo(newTarget)
        lastKnownPosition.set(newPosition)
        player2.lastUpdateTime = timestamp
    }
}

data class Message(val timestamp: Long, val position: Vector2, val target: Vector2)

class GameScreen : KtxScreen {
    private val image = Texture("circle.png".toInternalFile(), true).apply { setFilter(Linear, Linear) }
    private val batch = SpriteBatch()
    private var updatedNeeded = false
    private lateinit var viewport: Viewport
    // Player 1
    private lateinit var p1 : MyCharacter
    // Player 2: this one we are simulating network latency over
    private lateinit var p2 : MyCharacter
    private lateinit var dr : DeadReckoning

    // This is done as a means to simulate the sending/receiving messages
    private val queue: LinkedBlockingQueue<Message> = LinkedBlockingQueue()

    // Define a coroutine scope to let me declare coroutine that are going to run
    // without blocking the normal game loop
    private val customScope = CoroutineScope(Dispatchers.IO + Job())

    override fun show() {
        super.show()
        val camera = OrthographicCamera()
        camera.setToOrtho(true, 2400f, 1080f)
//        viewport = FitViewport(1200f, 540f, camera)
        viewport = ScreenViewport(camera)
        p1 = MyCharacter(Vector2(50f, 50f), Sprite(image))
        p2 = MyCharacter(Vector2(350f, 50f), Sprite(image))
        p2.sprite.color = com.badlogic.gdx.graphics.Color.BLUE
        dr = DeadReckoning(p2)
        // make a coroutine to get the simulated network messages and use it to update player 2
        customScope.launch {
            while (true) { // while true is not safe: this does not terminate
                // This is a LinkedBlockingQueue. `take` is blocking.
                queue.take().let {
                    dr.onNetworkUpdate(it.position.cpy().add(300f, 0f), it.target.cpy().add(300f, 0f), it.timestamp.toFloat())
                }
            }
        }
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
        dr.update(delta)
    }

    private fun input() {
        if (Gdx.input.isTouched) {
            val target = Vector2(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
            viewport.unproject(target)
            p1.moveTo(target)
            // "enqueue" the target position for the second player; do it in a delayed coroutine to simulate network latency
            // The scope of the coroutine should not be the same as the game loop
            customScope.launch {
                val timestamp = System.currentTimeMillis()
                delay(100 + Random.nextLong(30)) // induced latency 30 to 60 ms
                queue.add(Message(timestamp, p1.position.cpy(), target.cpy()))
            }
        }
    }

    override fun dispose() {
        image.disposeSafely()
        batch.disposeSafely()
    }
}
