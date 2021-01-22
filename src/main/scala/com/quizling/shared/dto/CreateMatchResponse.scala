package com.quizling.shared.dto

// Very simple HATEOAS-like links
case class Link(href: String)
class ResponseDto(_links: Map[String, Link])

/**
 * Response for request to create match
 *
 * @param id the id of the newly created match
 * @param _links the links of relevant match information (specifically the socket stream for now)
 */
case class CreateMatchResponse(id: String, var _links: Map[String, Link] = Map.empty) extends ResponseDto(_links) {

  /**
   * Add link to response
   *
   * @param name name of the link
   * @param href the href of the link
   * @return the modified response
   */
  def addLink(name: String, href: String): CreateMatchResponse = {
    _links += (name -> Link(href))
    this
  }
}