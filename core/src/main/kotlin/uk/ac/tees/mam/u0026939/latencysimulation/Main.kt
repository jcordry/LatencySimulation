package uk.ac.tees.mam.u0026939.latencysimulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
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

class MyCharacter(val id: Int, var position: Vector2, val sprite: Sprite) {
    var updateNeeded = false
    private val threshold = 0.01f
    private var targetPosition: Vector2? = null
    private var speed = 400f // Units per second, you can adjust it as needed
    var velocity: Vector2 = Vector2(0f, 0f)
    private var lastUpdateTime = 0L
    private var predictedPosition = position.cpy()

    init {
        sprite.setSize(32f, 32f)
        sprite.setOriginCenter()
    }

    fun moveTo(target: Vector2) {
        targetPosition = target
        velocity.set(target.cpy().sub(position).nor().scl(speed))
    }

    fun update(deltaTime: Float) {
        targetPosition?.let {
            val distanceToTarget = it.cpy().sub(position).len()
            val maxMoveDistance = speed * deltaTime

            // Updating the position
            if (distanceToTarget <= maxMoveDistance) {
                position.set(it)
                velocity.set(0f, 0f)
                targetPosition = null
            } else {
                position.add(velocity.cpy().scl(deltaTime))
            }

            // Updating the predicted position
            predictedPosition.add(velocity.cpy().scl(deltaTime))
            println("$id Position: $position Predicted position: $predictedPosition Match ${position.dst(predictedPosition)}")

            if (predictedPosition.dst(position) > threshold) {
                predictedPosition.set(position)
                lastUpdateTime = System.currentTimeMillis()
                println("$id Predicted position reset")
                updateNeeded = true
            } else {
                updateNeeded = false
            }
        }
        sprite.setPosition(position.x, position.y)
    }

    fun correctPosition(newPosition: Vector2, newVelocity: Vector2, target: Vector2, timestamp: Long) {
        val currentTime = System.currentTimeMillis()
        val latency = max(0L, currentTime - timestamp)

        // Predict where the entity should be based on the latency
        predictedPosition = newPosition.cpy().add(newVelocity.x * latency, newVelocity.y * latency)

        // Apply correction (simple interpolation)
        position.lerp(predictedPosition, 0.1f) // Smooth correction factor
        moveTo(target)
        // velocity should be based on the latency
        velocity.set(target.cpy().sub(position).nor().scl(speed*latency))
        lastUpdateTime = currentTime
    }

    fun draw(batch: SpriteBatch) {
        sprite.draw(batch)
    }
}

data class Message(val timestamp: Long, val position: Vector2, val velocity: Vector2, val target: Vector2)

class GameScreen : KtxScreen {
    private val image = Texture("circle.png".toInternalFile(), true).apply { setFilter(Linear, Linear) }
    private val batch = SpriteBatch()
    private lateinit var viewport: Viewport
    // Player 1
    private lateinit var p1 : MyCharacter
    // Player 2: this one we are simulating network latency over
    private lateinit var p2 : MyCharacter

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
        p1 = MyCharacter(1, Vector2(50f, 50f), Sprite(image))
        p2 = MyCharacter(2, Vector2(350f, 50f), Sprite(image))
        p2.sprite.color = com.badlogic.gdx.graphics.Color.BLUE
        // make a coroutine to get the simulated network messages and use it to update player 2
        customScope.launch {
            while (true) { // while true is not safe: this does not terminate
                // This is a LinkedBlockingQueue. `take` is blocking.
                queue.take().let {
                    println("Correcting position")
                    p2.correctPosition(it.position.cpy().add(300f, 0f), it.velocity, it.target.cpy().add(300f, 0f), it.timestamp)
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
        p2.update(delta)
    }

    private fun input() {
        if (Gdx.input.isTouched) {
            val target = Vector2(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
            viewport.unproject(target)
            p1.moveTo(target)
            // "enqueue" the target position for the second player; do it in a delayed coroutine to simulate network latency
            // The scope of the coroutine should not be the same as the game loop
            if (p1.updateNeeded) {
                val velocity = p1.velocity.cpy()
                val position = p1.position.cpy()
                customScope.launch {
                    delay(100 + Random.nextLong(30)) // induced latency 30 to 60 ms
                    queue.add(Message(System.currentTimeMillis(), position, velocity, target.cpy()))
                }
            }
        }
    }

    override fun dispose() {
        image.disposeSafely()
        batch.disposeSafely()
    }
}
