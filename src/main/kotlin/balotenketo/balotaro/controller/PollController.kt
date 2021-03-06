@file:Suppress("unused")

package balotenketo.balotaro.controller

import balotenketo.balotaro.model.*
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiResponse
import io.swagger.annotations.ApiResponses
import kondorcet.model.emptyPoll
import kondorcet.model.getResult
import kondorcet.model.plus
import kondorcet.model.pollOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest


@Api("Polls", description = "Polls management")
@RestController
class PollController {

    @Autowired
    lateinit var pollRepository: PollRepository

    @Autowired
    lateinit var tokenRepository: VoteTokenRepository

    @Autowired
    lateinit var ballotRepository: BallotRepository

    @ApiOperation("Create a new poll")
    @ApiResponses(*arrayOf(
            ApiResponse(code = 201, message = "Poll created"),
            ApiResponse(code = 400, message = "Invalid number of candidates"),
            ApiResponse(code = 403, message = "Maximum number of poll exceeded")))
    @RequestMapping("/poll/create",
            method = arrayOf(RequestMethod.POST),
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody argument: PollCreationArgument, request: HttpServletRequest): PollCreationResult {

        request.assertCreatedQuota(argument.tokenCount + 1, pollRepository, tokenRepository, ballotRepository)

        val poll = Poll(
                creatorIP = request.remoteAddr,
                isSecure = argument.secure,
                candidates = argument.candidates.toSet(),
                method = VoteMethod.of(argument.method)
        )

        pollRepository.save(poll)

        return PollCreationResult(
                poll = encode(poll.id, poll.secret),
                tokens = createTokens(poll, request.remoteAddr, argument.tokenCount)
        )
    }

    @ApiOperation("Create new tokens for a poll")
    @ApiResponses(*arrayOf(
            ApiResponse(code = 201, message = "Tokens created"),
            ApiResponse(code = 400, message = "Poll unspecified"),
            ApiResponse(code = 404, message = "Unknown poll")))
    @RequestMapping("/poll/createTokens",
            method = arrayOf(RequestMethod.POST),
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    @ResponseStatus(HttpStatus.CREATED)
    fun createTokens(@RequestBody argument: TokenCreationArgument, request: HttpServletRequest): List<String> {
        val poll = pollRepository[argument.poll]

        request.assertCreatedQuota(argument.tokenCount, pollRepository, tokenRepository, ballotRepository)

        return createTokens(poll, request.remoteAddr, argument.tokenCount)
    }

    @ApiOperation("Close a poll and return the result")
    @ApiResponses(*arrayOf(
            ApiResponse(code = 200, message = "Poll closed an result returned"),
            ApiResponse(code = 400, message = "Poll unspecified"),
            ApiResponse(code = 404, message = "Unknown poll")))
    @RequestMapping("/poll/close",
            method = arrayOf(RequestMethod.DELETE),
            consumes = arrayOf(MediaType.APPLICATION_JSON_VALUE),
            produces = arrayOf(MediaType.APPLICATION_JSON_VALUE))
    @ResponseStatus(HttpStatus.OK)
    fun close(@RequestBody argument: PollClosingArgument): List<Set<String>> {
        val poll = pollRepository[argument.poll]

        val result = ballotRepository.findByPoll(poll)
                .fold(emptyPoll<String>()) { p, b -> p + b }
                .getResult(poll.method.implementation)
                .orderedCandidates

        tokenRepository.deleteByPoll(poll)
        ballotRepository.deleteByPoll(poll)
        pollRepository.delete(poll)

        return result
    }

    fun createTokens(poll: Poll, ip: String, count: Int) =
            (1..count).map { VoteToken(ip, poll).apply { tokenRepository.save(this) }.let { encode(it.id, it.secret) } }
}
