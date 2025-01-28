import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.sqlite.SQLiteDataSource
import java.security.SecureRandom
import java.util.*

fun main() {
    embeddedServer(Netty, port = 3000) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            method(HttpMethod.Get)
            method(HttpMethod.Post)
            method(HttpMethod.Put)
            method(HttpMethod.Delete)
            header(HttpHeaders.ContentType)
            allowCredentials = true
            anyHost()
            host("https://front-registros.vercel.app") // Substitua pela URL exata do frontend
        }

        val db = SQLiteDataSource().apply {
            url = "jdbc:sqlite:./events.db"
        }

        // Criar tabelas
        db.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT,
                        time TEXT,
                        maxSlots INTEGER,
                        observations TEXT,
                        password TEXT
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS participants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        eventId INTEGER,
                        nickname TEXT,
                        vocation TEXT,
                        FOREIGN KEY (eventId) REFERENCES events (id)
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reservations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        eventId INTEGER,
                        nickname TEXT,
                        vocation TEXT,
                        FOREIGN KEY (eventId) REFERENCES events (id)
                    )
                """.trimIndent())
            }
        }

        routing {
            // Obter todos os eventos
            get("/events") {
                db.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery("SELECT * FROM events")
                        val events = mutableListOf<Map<String, Any>>()
                        while (rs.next()) {
                            events.add(mapOf(
                                "id" to rs.getInt("id"),
                                "name" to rs.getString("name"),
                                "time" to rs.getString("time"),
                                "maxSlots" to rs.getInt("maxSlots"),
                                "observations" to rs.getString("observations"),
                                "password" to rs.getString("password")
                            ))
                        }
                        call.respond(events)
                    }
                }
            }

            // Obter participantes de um evento
            get("/events/{id}/participants") {
                val eventId = call.parameters["id"]?.toIntOrNull()
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event ID"))
                    return@get
                }

                db.connection.use { conn ->
                    conn.prepareStatement("SELECT * FROM participants WHERE eventId = ?").use { stmt ->
                        stmt.setInt(1, eventId)
                        val rs = stmt.executeQuery()
                        val participants = mutableListOf<Map<String, Any>>()
                        while (rs.next()) {
                            participants.add(mapOf(
                                "id" to rs.getInt("id"),
                                "eventId" to rs.getInt("eventId"),
                                "nickname" to rs.getString("nickname"),
                                "vocation" to rs.getString("vocation")
                            ))
                        }
                        call.respond(participants)
                    }
                }
            }

            // Obter reservas de um evento
            get("/events/{id}/reservations") {
                val eventId = call.parameters["id"]?.toIntOrNull()
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event ID"))
                    return@get
                }

                db.connection.use { conn ->
                    conn.prepareStatement("SELECT * FROM reservations WHERE eventId = ?").use { stmt ->
                        stmt.setInt(1, eventId)
                        val rs = stmt.executeQuery()
                        val reservations = mutableListOf<Map<String, Any>>()
                        while (rs.next()) {
                            reservations.add(mapOf(
                                "id" to rs.getInt("id"),
                                "eventId" to rs.getInt("eventId"),
                                "nickname" to rs.getString("nickname"),
                                "vocation" to rs.getString("vocation")
                            ))
                        }
                        call.respond(reservations)
                    }
                }
            }

            // Adicionar um novo evento
            post("/events") {
                val event = call.receive<Map<String, Any>>()
                val name = event["name"] as? String
                val time = event["time"] as? String
                val maxSlots = event["maxSlots"] as? Int
                val observations = event["observations"] as? String ?: "[]"
                val password = generatePassword()

                if (name == null || time == null || maxSlots == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing required fields"))
                    return@post
                }

                db.connection.use { conn ->
                    conn.prepareStatement("""
                        INSERT INTO events (name, time, maxSlots, observations, password)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()).use { stmt ->
                        stmt.setString(1, name)
                        stmt.setString(2, time)
                        stmt.setInt(3, maxSlots)
                        stmt.setString(4, observations)
                        stmt.setString(5, password)
                        stmt.executeUpdate()

                        val rs = stmt.generatedKeys
                        if (rs.next()) {
                            call.respond(mapOf("id" to rs.getInt(1), "password" to password))
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to insert event"))
                        }
                    }
                }
            }

            // Adicionar participante ou reserva a um evento
            post("/events/{id}/participants") {
                val eventId = call.parameters["id"]?.toIntOrNull()
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event ID"))
                    return@post
                }

                val participant = call.receive<Map<String, Any>>()
                val nickname = participant["nickname"] as? String
                val vocation = participant["vocation"] as? String

                if (nickname == null || vocation == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing required fields"))
                    return@post
                }

                db.connection.use { conn ->
                    // Verificar quantos participantes já estão registrados
                    conn.prepareStatement("SELECT COUNT(*) AS count FROM participants WHERE eventId = ?").use { stmt ->
                        stmt.setInt(1, eventId)
                        val rs = stmt.executeQuery()
                        val currentCount = if (rs.next()) rs.getInt("count") else 0

                        // Obter maxSlots do evento
                        conn.prepareStatement("SELECT maxSlots FROM events WHERE id = ?").use { stmt2 ->
                            stmt2.setInt(1, eventId)
                            val rs2 = stmt2.executeQuery()
                            if (!rs2.next()) {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                                return@post
                            }

                            val maxSlots = rs2.getInt("maxSlots")

                            if (currentCount < maxSlots) {
                                // Adicionar participante
                                conn.prepareStatement("""
                                    INSERT INTO participants (eventId, nickname, vocation)
                                    VALUES (?, ?, ?)
                                """.trimIndent()).use { stmt3 ->
                                    stmt3.setInt(1, eventId)
                                    stmt3.setString(2, nickname)
                                    stmt3.setString(3, vocation)
                                    stmt3.executeUpdate()

                                    val rs3 = stmt3.generatedKeys
                                    if (rs3.next()) {
                                        call.respond(mapOf("id" to rs3.getInt(1)))
                                    } else {
                                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to insert participant"))
                                    }
                                }
                            } else {
                                // Adicionar à lista de reservas
                                conn.prepareStatement("""
                                    INSERT INTO reservations (eventId, nickname, vocation)
                                    VALUES (?, ?, ?)
                                """.trimIndent()).use { stmt3 ->
                                    stmt3.setInt(1, eventId)
                                    stmt3.setString(2, nickname)
                                    stmt3.setString(3, vocation)
                                    stmt3.executeUpdate()

                                    val rs3 = stmt3.generatedKeys
                                    if (rs3.next()) {
                                        call.respond(mapOf("message" to "Added to reservation list"))
                                    } else {
                                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to insert reservation"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Deletar um evento
            delete("/events/{id}") {
                val eventId = call.parameters["id"]?.toIntOrNull()
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid event ID"))
                    return@delete
                }

                val password = call.receive<Map<String, Any>>()["password"] as? String
                if (password == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing password"))
                    return@delete
                }

                db.connection.use { conn ->
                    conn.prepareStatement("SELECT password FROM events WHERE id = ?").use { stmt ->
                        stmt.setInt(1, eventId)
                        val rs = stmt.executeQuery()
                        if (!rs.next()) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
                            return@delete
                        }

                        val storedPassword = rs.getString("password")
                        if (storedPassword != password) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid password"))
                            return@delete
                        }

                        // Deletar evento, participantes e reservas
                        conn.prepareStatement("DELETE FROM events WHERE id = ?").use { stmt2 ->
                            stmt2.setInt(1, eventId)
                            stmt2.executeUpdate()
                        }

                        conn.prepareStatement("DELETE FROM participants WHERE eventId = ?").use { stmt2 ->
                            stmt2.setInt(1, eventId)
                            stmt2.executeUpdate()
                        }

                        conn.prepareStatement("DELETE FROM reservations WHERE eventId = ?").use { stmt2 ->
                            stmt2.setInt(1, eventId)
                            stmt2.executeUpdate()
                        }

                        call.respond(mapOf("message" to "Event, participants, and reservations deleted"))
                    }
                }
            }
        }
    }.start(wait = true)
}

// Função para gerar senha aleatória
fun generatePassword(): String {
    val random = SecureRandom()
    val bytes = ByteArray(4)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
