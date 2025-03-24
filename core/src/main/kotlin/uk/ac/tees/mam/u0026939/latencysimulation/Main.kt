package uk.ac.tees.mam.u0026939.latencysimulation

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.FitViewport
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

class MyCharacter(private var position: Vector2, val sprite: Sprite) {
    private var targetPosition: Vector2? = null
    private var speed = 400f // Units per second, you can adjust it as needed
    private var velocity: Vector2 = Vector2(0f, 0f)

    // This is used to keep track of the last input received
    // I am cheating a bit, because I know in this case that the two
    // players are going to be updated in the same frame.
    // In real life, you would need to keep track of the time of the last
    // input received for each player and update based on the server tick.
    var timestamp = 0

    init {
        sprite.setSize(32f, 32f)
        sprite.setOriginCenter()
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

// Simple data class to represent the input received from the player
// The input is just a direction vector
data class PlayerInput(val playerId: Int, val action: Vector2, val timestamp: Int)

// This class is used to manage the lockstep protocol
// When a frame is complete, we process the frame and move on to the next tick
data class LockstepFrame(val tick: Int, val inputs: MutableList<PlayerInput> = mutableListOf())

class LockstepManager(private val players: List<MyCharacter>) {
    // This is a map of inputs received for each tick
    private val inputBuffer = mutableMapOf<Int, LockstepFrame>()
    private var currentTick = 0

    fun receiveInput(input: PlayerInput) {
        // create the frame if it does not exist yet.
        val frame = inputBuffer.getOrPut(input.timestamp) { LockstepFrame(input.timestamp) }
        // Note: this is not safe for concurrent access
        players[input.playerId].timestamp++
        frame.inputs.add(input)

        // If we have all inputs for this tick, process the frame
        if (frame.inputs.size == players.size) {
            processFrame(frame)
        }
    }

    private fun processFrame(frame: LockstepFrame) {
        // Apply all player inputs for this tick
        for (input in frame.inputs) {
            applyInput(input)
        }

        // Move to the next tick
        // Not doing much with the tick at the moment.
        // Ideally it should match the server's tick.
        currentTick++
    }

    private fun applyInput(input: PlayerInput) {
        // Apply game logic here
        // In this case, just move to the location.
        // We could deal with collision.
        // This is deterministic.
        players[input.playerId].moveTo(input.action)
    }
}

class GameScreen : KtxScreen {
    private val image = Texture("circle.png".toInternalFile(), true).apply { setFilter(Linear, Linear) }
    private val batch = SpriteBatch()
    private lateinit var viewport: FitViewport

    // Player 1
    private lateinit var p1 : MyCharacter
    // Player 2: we simulate network latency over this one.
    private lateinit var p2 : MyCharacter
    private lateinit var lockstepManager: LockstepManager

    // This is done as a means to simulate the sending/receiving messages
    // Blocking queue is used
    private val queue: LinkedBlockingQueue<Vector2> = LinkedBlockingQueue()

    // Define a coroutine scope to let us declare coroutines that are going to run
    // without blocking the normal game loop
    private val customScope = CoroutineScope(Dispatchers.IO + Job())

    override fun show() {
        super.show()
        val camera = OrthographicCamera()
        camera.setToOrtho(true, 1200f, 540f)
        viewport = FitViewport(1200f, 540f, camera)
        p1 = MyCharacter(Vector2(50f, 50f), Sprite(image))
        p2 = MyCharacter(Vector2(350f, 50f), Sprite(image))
        p2.sprite.color = com.badlogic.gdx.graphics.Color.BLUE
        lockstepManager = LockstepManager(listOf(p1, p2))

        // make a coroutine to get the simulated network messages and use it to update player 2
        customScope.launch {
            while (true) { // while true is not safe: this does not terminate
                // Note: we are getting the data from a blocking queue here.
                // We block until we receive a message.
                queue.take().let {
                    // not verifying the validity of the input, this could be the server's job
                    // here, we just simulate receiving the input from the server
                    lockstepManager.receiveInput(PlayerInput(1, it.cpy().add(300f, 0f), p2.timestamp))
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
            // Get the input
            val target = Vector2(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
            // Make sure the coordinates match the viewport ones.
            viewport.unproject(target)

            // we are telling the lockstepManager about the input from the player here
            // this is much earlier than the "remote" player's input (p2)
            // They both are going to start moving at the same time, though
            lockstepManager.receiveInput(PlayerInput(0, target, p1.timestamp))

            // "enqueue" the target position for the second player; do it in a delayed coroutine to simulate network latency
            // The scope of the coroutine should not block the game loop
            customScope.launch {
                delay(30 + Random.nextLong(30)) // induced latency 30 to 60 ms
                queue.add(target)
            }
        }
    }

    override fun dispose() {
        image.disposeSafely()
        batch.disposeSafely()
    }
}
