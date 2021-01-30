# Quizling Engine
## An Akka-based quiz engine
* NOTE: This project is first and foremost a learning experience. At no point do I plan to actually
 deploy this for an audience larger than myself

This is a project which is meant to provide the engine for a quiz application 
(rather nonsensically called Quizling) allowing multiple users to compete against each while taking a quiz. It
was inspired by [*kahoot!*](http://kahoot.com).
It is written in Scala, built on
Akka, and writes records to MongoDB.

This is my first project using both Scala and Akka so there are certainly lots of mistakes that could be improved throughout the project. 
It's a work-in progress both in terms of adding features and refactoring. If you have suggestions feel free to open an issue; I'd love to hear
about things that could be improved.
Use any code snippets with the knowledge that they could be completely wrong.

### Starting the Application
Run the application using the command `sbt run`

### Interacting with the Application
Once the aforementioned frontend is "done" interaction will be easier. Until then, the HTTP endpoint
`POST {host:port}/match` will start a match; a sample JSON for this endpoint can be found in 
the file `docs/samples/StartMatch.json`. This will return a match id and an href to the match stream which is a websocket
in which all match events are sent to; connecting to that socket will allow you to receive match events and
send in answers (sample question response given in `docs/samples/SubmitAnswerSocketDto.json`). Before the match starts
all participants---as determined from the `participants` field in the start match request---must send a "participant-ready"
socket message; a sample of this message can be found in `docs/samples/ParticipantReadySocketDto.json`.

Note that the `@type` field present in the socket messages given above is a unique identifier to allow the messages
to be deserialized so this field cannot be changed. See the `SocketJsonSupport.scala` file for more information
(and that area is particularly bad code so beware)

### Application Configuration
There are two main configurations to set both of which are found in the `application.conf` file:
1. `system.app.http` contains the `host` and `port` variables which determines the host and 
port for the http server (which uses Akka HTTP)
2. `system.db` contains the host (`host`), database name (`name`), and Mongo collection name (`match.collection.name`) for the Mongo
collection that will store results of quiz matches

## Future: Larger Quizling Ecosystem
The idea is to have this be the first of a sequence of projects to "build" a quiz app which 
was inspired by [*kahoot!*](http://kahoot.com) but I'm sure there are many similar apps that do this. This project is
meant to be followed by:
1. A simple frontend allowing users to actually "play" each
2. A "trivia service" which would allow users to create their own custom quizzes. These custom
quizzes could be sent to this project and this engine would allow matches to be completed using the custom quizzes
3. A "match statistics service" which would allow users to see statistics for their matches and, perhaps,
build experience points or level up or something similar